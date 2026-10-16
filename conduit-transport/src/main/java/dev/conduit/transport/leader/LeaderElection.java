package dev.conduit.transport.leader;

import dev.conduit.transport.adapter.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * transport 单活多备的选主协调器（V0.2-R spec 3.3），随 Spring 生命周期启停，取代原先的 AdapterLifecycle。
 * 备实例每 acquire-interval 尝试抢锁；抢到后成为主实例，在独立线程上启动全部协议适配器（失败按 1s→10s 退避重试）。
 * 主实例每 renew-interval 续期，续期失败（锁已不属于本实例、Redis 出错或超时）立即停止适配器并转为备用；
 * 每秒检查适配器是否需要重连，重连前先续期确认仍持锁。正常停止时停止适配器并释放锁，备实例可立即接管。
 * Redis 不可用时所有实例都不连接 EMQX，不会出现无锁的主实例。
 */
public class LeaderElection implements SmartLifecycle {

    /** 实例当前角色。 */
    public enum Role { LEADER, STANDBY }

    static final Duration SUPERVISE_INTERVAL = Duration.ofSeconds(1);
    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(LeaderElection.class);

    private final LeaderLock lock;
    private final List<ProtocolAdapter> adapters;
    private final LeaderProperties props;
    private final Duration superviseInterval;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    /** 每次角色变化加一；旧任期的启动循环据此放弃。 */
    private final AtomicLong term = new AtomicLong();
    private final AtomicBoolean restarting = new AtomicBoolean();
    private volatile Role role = Role.STANDBY;
    private volatile boolean running;
    /** 上一次抢锁是否因 Redis 不可用而失败：只在状态切换时打 WARN / INFO，其余失败降为 DEBUG，避免每 2 秒刷屏。仅调度线程读写。 */
    private boolean redisAcquireFailing;
    private ScheduledExecutorService scheduler;
    private ExecutorService adapterExecutor;

    public LeaderElection(LeaderLock lock, List<ProtocolAdapter> adapters, LeaderProperties props) {
        this(lock, adapters, props, SUPERVISE_INTERVAL, INITIAL_BACKOFF, MAX_BACKOFF);
    }

    LeaderElection(LeaderLock lock, List<ProtocolAdapter> adapters, LeaderProperties props,
                   Duration superviseInterval, Duration initialBackoff, Duration maxBackoff) {
        this.lock = lock;
        this.adapters = List.copyOf(adapters);
        this.props = props;
        this.superviseInterval = superviseInterval;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    public Role role() {
        return role;
    }

    public String instanceId() {
        return lock.instanceId();
    }

    /** 是否为主实例且全部适配器已连接（健康检查的 connected）。 */
    public boolean adaptersConnected() {
        return role == Role.LEADER && !adapters.isEmpty() && adapters.stream().allMatch(ProtocolAdapter::isConnected);
    }

    /** 启动调度线程：立即尝试一次抢锁，之后按配置的间隔抢锁、续期与检查适配器。重复调用无效果。 */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("transport-leader").daemon().factory());
        adapterExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("transport-adapter").daemon().factory());
        scheduler.scheduleWithFixedDelay(guard("acquire", this::acquireTick), 0,
                props.acquireInterval().toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(guard("renew", this::renewTick), props.renewInterval().toMillis(),
                props.renewInterval().toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(guard("supervise", this::superviseTick), superviseInterval.toMillis(),
                superviseInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Leader election started as instance {} (ttl={}, renew every {}, acquire every {})",
                lock.instanceId(), props.ttl(), props.renewInterval(), props.acquireInterval());
    }

    /**
     * 停止：先停调度（不再抢锁或续期），再停止适配器与启动线程；本实例是主实例时释放锁（比对 id 后删除），
     * 释放失败只记 WARN，锁在 ttl 后自然过期。
     */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        // 先记下角色再停调度：进行中的续期可能被中断，不能因此漏掉释放锁
        boolean wasLeader = role == Role.LEADER;
        scheduler.shutdownNow();
        awaitQuietly(scheduler);
        // 停调度期间进行中的抢锁可能刚刚成功；release 只删除仍是本实例 id 的键，多释放一次无害
        wasLeader = wasLeader || role == Role.LEADER;
        term.incrementAndGet();
        role = Role.STANDBY;
        stopAdapters();
        adapterExecutor.shutdownNow();
        awaitQuietly(adapterExecutor);
        if (wasLeader) {
            try {
                boolean released = lock.release();
                log.info("Leader {} on shutdown by instance {}", released ? "lock released" : "lock was no longer held",
                        lock.instanceId());
            } catch (RuntimeException e) {
                log.warn("Could not release the leader lock on shutdown (it expires after {}): {}", props.ttl(),
                        e.getMessage());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 备实例尝试抢锁；抢到即成为主实例并异步启动适配器。Redis 不可用时保持备用，只记 WARN。 */
    void acquireTick() {
        if (role == Role.LEADER) {
            return;
        }
        boolean acquired;
        try {
            acquired = lock.tryAcquire();
        } catch (RuntimeException e) {
            if (!redisAcquireFailing) {
                redisAcquireFailing = true;
                log.warn("Cannot reach Redis to acquire the leader lock, staying standby: {}", e.getMessage());
            } else {
                log.debug("Redis still unreachable for the leader lock: {}", e.getMessage());
            }
            return;
        }
        if (redisAcquireFailing) {
            redisAcquireFailing = false;
            log.info("Redis is reachable again, instance {} resumes acquiring the leader lock", lock.instanceId());
        }
        if (!acquired) {
            log.debug("Leader lock is held by another instance, {} stays standby", lock.instanceId());
            return;
        }
        long t = term.incrementAndGet();
        role = Role.LEADER;
        log.info("Instance {} became leader, starting protocol adapters", lock.instanceId());
        submit(() -> startAdapters(t));
    }

    /** 主实例续期；失败则下台（见 renewOrStepDown）。 */
    void renewTick() {
        if (role != Role.LEADER) {
            return;
        }
        if (renewOrStepDown()) {
            log.debug("Leader lock renewed by {}", lock.instanceId());
        }
    }

    /**
     * 主实例检查适配器：有适配器需要重连时，先续期确认仍持锁（失败则下台、不重连，避免与新主互相踢），
     * 再在适配器线程上 stop 后重新 start。同一时刻只有一个重连任务。
     */
    void superviseTick() {
        if (role != Role.LEADER || restarting.get()) {
            return;
        }
        List<ProtocolAdapter> broken = adapters.stream().filter(ProtocolAdapter::needsRestart).toList();
        if (broken.isEmpty() || !renewOrStepDown()) {
            return;
        }
        long t = term.get();
        restarting.set(true);
        boolean submitted = submit(() -> {
            try {
                for (ProtocolAdapter adapter : broken) {
                    log.info("Reconnecting protocol adapter {} as leader {}", adapter.name(), lock.instanceId());
                    adapter.stop();
                    startAdapter(adapter, t);
                }
            } finally {
                restarting.set(false);
            }
        });
        if (!submitted) {
            restarting.set(false);
        }
    }

    /**
     * 续期锁；锁已不属于本实例或 Redis 出错时 WARN 并下台：任期加一、转为备用、立即停止全部适配器。
     *
     * @return 是否仍是主实例
     */
    private boolean renewOrStepDown() {
        String problem;
        try {
            if (lock.renew()) {
                return true;
            }
            problem = "the lock is no longer held by this instance";
        } catch (RuntimeException e) {
            problem = "Redis error: " + e.getMessage();
        }
        log.warn("Leader lock renewal failed for {} ({}), stopping protocol adapters and becoming standby",
                lock.instanceId(), problem);
        term.incrementAndGet();
        role = Role.STANDBY;
        stopAdapters();
        log.info("Instance {} became standby", lock.instanceId());
        return false;
    }

    /** 依次启动全部适配器（任期 t 内）。 */
    private void startAdapters(long t) {
        for (ProtocolAdapter adapter : adapters) {
            startAdapter(adapter, t);
        }
    }

    /**
     * 在任期 t 仍有效时反复尝试启动一个适配器，失败按 initialBackoff 起翻倍、最多 maxBackoff 退避。
     * 只有首次失败打 WARN，之后的重试打 DEBUG，成功后（若此前失败过）的 INFO 带上失败次数。
     * 任期失效（下台或停止）时适配器自己会放弃启动，这里据此静默退出；启动成功后若发现已下台，立即停止它。线程被中断时放弃。
     */
    private void startAdapter(ProtocolAdapter adapter, long t) {
        for (int attempt = 0; running && term.get() == t; attempt++) {
            try {
                // 把任期校验交给适配器，使其在发出 CONNECT 与订阅前再确认一次，下台后不会再连上 broker
                adapter.start(() -> running && term.get() == t);
                if (!running || term.get() != t) {
                    adapter.stop();
                    return;
                }
                if (attempt > 0) {
                    log.info("Protocol adapter {} connected on leader {} after {} failed attempts", adapter.name(),
                            lock.instanceId(), attempt);
                } else {
                    log.info("Protocol adapter {} connected on leader {}", adapter.name(), lock.instanceId());
                }
                return;
            } catch (RuntimeException e) {
                if (!running || term.get() != t) {
                    log.debug("Protocol adapter {} start abandoned after the term ended: {}", adapter.name(),
                            e.getMessage());
                    return;
                }
                Duration delay = backoff(attempt);
                if (attempt == 0) {
                    log.warn("Protocol adapter {} failed to start, retrying in {} ms: {}", adapter.name(),
                            delay.toMillis(), e.getMessage());
                } else {
                    log.debug("Protocol adapter {} failed to start (attempt {}), retrying in {} ms: {}",
                            adapter.name(), attempt + 1, delay.toMillis(), e.getMessage());
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 停止全部适配器，单个失败只记 WARN。 */
    private void stopAdapters() {
        for (ProtocolAdapter adapter : adapters) {
            try {
                adapter.stop();
            } catch (RuntimeException e) {
                log.warn("Protocol adapter {} did not stop cleanly: {}", adapter.name(), e.getMessage());
            }
        }
    }

    private Duration backoff(int attempt) {
        Duration delay = initialBackoff.multipliedBy(1L << Math.min(attempt, 10));
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    /** 提交到适配器线程；协调器正在停止时返回 false。 */
    private boolean submit(Runnable task) {
        try {
            adapterExecutor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            log.debug("Adapter task skipped, leader election is stopping");
            return false;
        }
    }

    /** 包装定时任务：异常只记日志，避免 scheduleWithFixedDelay 因异常停止后续执行。 */
    private Runnable guard(String what, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Leader election {} tick failed unexpectedly", what, e);
            }
        };
    }

    private static void awaitQuietly(ExecutorService executor) {
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
