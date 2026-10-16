package dev.conduit.transport.leader;

import dev.conduit.transport.adapter.ProtocolAdapter;
import dev.conduit.transport.leader.LeaderElection.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * LeaderElection 单元测试（内存锁 + 假适配器，毫秒级间隔）：抢到锁才启动适配器、别人持锁时保持备用、
 * 两个实例只有一个成为主、续期失败或 Redis 出错立即停止适配器并转为备用、适配器启动失败按退避重试、
 * 断线后确认仍持锁才重启、失锁时不重启、停止时只释放自己持有的锁。
 */
class LeaderElectionTest {

    static final LeaderProperties PROPS = new LeaderProperties("k", Duration.ofMillis(500),
            Duration.ofMillis(50), Duration.ofMillis(50));
    static final Duration WAIT = Duration.ofSeconds(5);

    final LockState state = new LockState();
    final List<LeaderElection> elections = new ArrayList<>();

    @AfterEach
    void stopAll() {
        elections.forEach(LeaderElection::stop);
    }

    private LeaderElection election(String id, FakeAdapter adapter) {
        LeaderElection e = new LeaderElection(new FakeLock(state, id), List.of(adapter), PROPS,
                Duration.ofMillis(50), Duration.ofMillis(10), Duration.ofMillis(40));
        elections.add(e);
        return e;
    }

    @Test
    void becomesLeaderStartsTheAdapterAndReleasesTheLockOnStop() {
        FakeAdapter adapter = new FakeAdapter();
        LeaderElection e = election("a", adapter);

        e.start();

        await().atMost(WAIT).until(() -> e.role() == Role.LEADER && e.adaptersConnected());
        assertThat(adapter.starts).hasValue(1);
        assertThat(state.holder()).isEqualTo("a");

        e.stop();

        assertThat(e.role()).isEqualTo(Role.STANDBY);
        assertThat(adapter.connected).isFalse();
        assertThat(state.holder()).isNull();
    }

    @Test
    void staysStandbyWhileAnotherInstanceHoldsTheLock() throws Exception {
        state.set("someone-else");
        FakeAdapter adapter = new FakeAdapter();
        LeaderElection e = election("a", adapter);

        e.start();
        Thread.sleep(300);

        assertThat(e.role()).isEqualTo(Role.STANDBY);
        assertThat(adapter.starts).hasValue(0);
        assertThat(e.adaptersConnected()).isFalse();

        state.set(null);
        await().atMost(WAIT).until(() -> e.role() == Role.LEADER && e.adaptersConnected());
    }

    @Test
    void onlyOneOfTwoInstancesBecomesLeader() throws Exception {
        FakeAdapter adapterA = new FakeAdapter();
        FakeAdapter adapterB = new FakeAdapter();
        LeaderElection a = election("a", adapterA);
        LeaderElection b = election("b", adapterB);

        a.start();
        b.start();

        await().atMost(WAIT).until(() -> a.role() == Role.LEADER || b.role() == Role.LEADER);
        for (int i = 0; i < 25; i++) {
            assertThat(elections.stream().filter(e -> e.role() == Role.LEADER).count()).isEqualTo(1);
            Thread.sleep(20);
        }
        LeaderElection standby = a.role() == Role.LEADER ? b : a;
        FakeAdapter standbyAdapter = standby == a ? adapterA : adapterB;
        assertThat(standbyAdapter.starts).hasValue(0);
    }

    @Test
    void aLostLockStopsTheAdapterAndMakesTheInstanceStandby() {
        FakeAdapter adapter = new FakeAdapter();
        LeaderElection e = election("a", adapter);
        e.start();
        await().atMost(WAIT).until(e::adaptersConnected);

        state.set("someone-else");

        await().atMost(WAIT).until(() -> e.role() == Role.STANDBY);
        assertThat(adapter.connected).isFalse();
        assertThat(adapter.stops.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aRedisErrorDuringRenewalStepsDownAndTheInstanceReturnsWhenRedisRecovers() {
        FakeAdapter adapter = new FakeAdapter();
        LeaderElection e = election("a", adapter);
        e.start();
        await().atMost(WAIT).until(e::adaptersConnected);

        state.redisDown(true);

        await().atMost(WAIT).until(() -> e.role() == Role.STANDBY && !adapter.connected);

        state.set(null); // 锁已过期
        state.redisDown(false);
        await().atMost(WAIT).until(() -> e.role() == Role.LEADER && e.adaptersConnected());
    }

    @Test
    void retriesAFailingAdapterStartWithBackoffUntilItConnects() {
        FakeAdapter adapter = new FakeAdapter();
        adapter.failuresLeft.set(2);
        LeaderElection e = election("a", adapter);

        e.start();

        await().atMost(WAIT).until(e::adaptersConnected);
        assertThat(adapter.starts).hasValue(3);
    }

    @Test
    void restartsAnAdapterThatLostItsConnectionWhileStillLeader() {
        FakeAdapter adapter = new FakeAdapter();
        LeaderElection e = election("a", adapter);
        e.start();
        await().atMost(WAIT).until(e::adaptersConnected);

        adapter.dropConnection();

        await().atMost(WAIT).until(() -> adapter.starts.get() == 2 && adapter.connected);
        assertThat(e.role()).isEqualTo(Role.LEADER);
    }

    @Test
    void doesNotRestartTheAdapterWhenTheLockWasLostMeanwhile() throws Exception {
        FakeAdapter adapter = new FakeAdapter();
        LeaderElection e = election("a", adapter);
        e.start();
        await().atMost(WAIT).until(e::adaptersConnected);

        state.set("new-leader");
        adapter.dropConnection();

        await().atMost(WAIT).until(() -> e.role() == Role.STANDBY);
        Thread.sleep(200);
        assertThat(adapter.starts).hasValue(1);
    }

    @Test
    void stopDoesNotReleaseALockHeldByAnotherInstance() throws Exception {
        state.set("someone-else");
        LeaderElection e = election("a", new FakeAdapter());
        e.start();
        Thread.sleep(150);

        e.stop();

        assertThat(state.holder()).isEqualTo("someone-else");
    }

    /** 多个 FakeLock 共享的锁状态，模拟同一个 Redis 键；redisDown 时所有操作抛异常。 */
    static final class LockState {
        private String holder;
        private boolean redisDown;

        synchronized String holder() {
            return holder;
        }

        synchronized void set(String value) {
            holder = value;
        }

        synchronized void redisDown(boolean down) {
            redisDown = down;
        }
    }

    /** 内存中的 LeaderLock：语义与 RedisLeaderLock 相同（SET NX、比对后续期与释放），不计过期。 */
    static final class FakeLock implements LeaderLock {
        private final LockState state;
        private final String id;

        FakeLock(LockState state, String id) {
            this.state = state;
            this.id = id;
        }

        @Override
        public String instanceId() {
            return id;
        }

        @Override
        public boolean tryAcquire() {
            synchronized (state) {
                check();
                if (state.holder != null) {
                    return false;
                }
                state.holder = id;
                return true;
            }
        }

        @Override
        public boolean renew() {
            synchronized (state) {
                check();
                return id.equals(state.holder);
            }
        }

        @Override
        public boolean release() {
            synchronized (state) {
                check();
                if (!id.equals(state.holder)) {
                    return false;
                }
                state.holder = null;
                return true;
            }
        }

        private void check() {
            if (state.redisDown) {
                throw new IllegalStateException("redis down");
            }
        }
    }

    /** 记录启停次数的假适配器：可设定前 N 次启动失败，dropConnection 模拟断线（需重启）。 */
    static final class FakeAdapter implements ProtocolAdapter {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger failuresLeft = new AtomicInteger();
        volatile boolean connected;
        volatile boolean restartRequested;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public void start() {
            starts.incrementAndGet();
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("broker unreachable");
            }
            connected = true;
            restartRequested = false;
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
            connected = false;
            restartRequested = false;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean needsRestart() {
            return restartRequested;
        }

        void dropConnection() {
            connected = false;
            restartRequested = true;
        }
    }
}
