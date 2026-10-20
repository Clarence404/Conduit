package dev.conduit.server.replay;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.pipeline.Backoff;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import dev.conduit.server.pipeline.IngestSource;
import dev.conduit.server.pipeline.ProcessingAbortedException;
import dev.conduit.server.pipeline.TelemetryIngestor;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 遥测重放（V0.2-R3 spec 4）：把总线上行流中保留的原始报文按时间范围重新写入时序库，用于 InfluxDB 断电丢数后的恢复。
 * 同一时刻只允许一个任务，状态保存在内存中的单个 AtomicReference，不持久化；最近一次完成的状态保留到下一次提交为止。
 * 与正常流水线共用 {@link TelemetryIngestor}，但<b>不更新在线状态、不写死信、不 ACK</b>——重放的是旧数据，
 * 会把已离线设备标成在线，死信也会被重复写入污染首页统计。写入幂等（同时间戳覆盖、最新值按 ts 比较），
 * 所以重放可以与正常消费并行，重复重放也无害。
 */
@Service
public class ReplayService {

    /** 每批从流中读取的条目数（spec 4.3）。 */
    static final int BATCH_SIZE = 500;

    /**
     * 可恢复错误（读流、写库）的最大尝试次数：配合 1s→30s 退避（1+2+4+8+16+30+30），约 90 秒后仍不可用就把任务置为 FAILED。
     * 取 8 是为了足够撑过一次 InfluxDB 重启，又不至于让取消被重试阶梯卡住太久；
     * 取消只在批次之间检查，所以不能设为不限次数，否则面对宕机的 InfluxDB 任务将无法取消。
     * 重放是人工发起的恢复动作，失败后重新提交即可，因此不做成配置项。
     */
    static final int MAX_ATTEMPTS = 8;

    /** Redis 流条目序号是无符号 64 位整数，取最大值作为「某毫秒内的最后一条」。 */
    private static final String MAX_SEQUENCE = "18446744073709551615";

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final MessageBus bus;
    private final TelemetryIngestor ingestor;
    private final Clock clock;
    private final Backoff backoff;
    private final Executor executor;
    private final AtomicReference<ReplayTask> current = new AtomicReference<>();

    @Autowired
    public ReplayService(MessageBus bus, TelemetryIngestor ingestor, Clock clock) {
        this(bus, ingestor, clock, new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30), MAX_ATTEMPTS),
                command -> Thread.ofVirtual().name("telemetry-replay").start(command));
    }

    /** 可替换退避与执行器的构造器，测试用（同步执行或先收下再执行，便于确定地断言取消与 409）。 */
    ReplayService(MessageBus bus, TelemetryIngestor ingestor, Clock clock, Backoff backoff, Executor executor) {
        this.bus = bus;
        this.ingestor = ingestor;
        this.clock = clock;
        this.backoff = backoff;
        this.executor = executor;
    }

    /**
     * 提交一次重放，立即返回提交那一刻的状态（RUNNING），实际执行在后台线程。
     *
     * @throws ApiException 400 参数非法（见 {@link ReplayRequest#validated()}）；409 已有任务正在运行
     */
    public ReplayStatus start(ReplayRequest request) {
        ReplayRequest valid = request.validated();
        ReplayTask existing = current.get();
        if (existing != null && existing.isRunning()) {
            throw ApiException.conflict("已有重放任务正在运行");
        }
        ReplayTask task = new ReplayTask(valid.from(), valid.to(), clock.millis());
        if (!current.compareAndSet(existing, task)) {
            throw ApiException.conflict("已有重放任务正在运行");
        }
        log.info("Telemetry replay submitted: from={} to={}", valid.from(), valid.to());
        // 先取快照再交给执行器：同步执行器会在 execute 返回前跑完任务，返回值仍须是提交那一刻的 RUNNING。
        ReplayStatus submitted = task.snapshot();
        try {
            executor.execute(() -> run(task));
        } catch (RuntimeException e) {
            // 提交失败时不清除 current，让操作员通过状态接口看到原因；任务置为 FAILED 后下一次提交不再被 409 挡住。
            String reason = "无法启动重放线程：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            task.finish(ReplayState.FAILED, reason, clock.millis());
            log.warn("Telemetry replay could not be started: {}", e.toString());
            return task.snapshot();
        }
        return submitted;
    }

    /** 当前（或最近一次）任务的状态；从未提交过返回 {@link ReplayStatus#none()}。 */
    public ReplayStatus status() {
        ReplayTask task = current.get();
        return task == null ? ReplayStatus.none() : task.snapshot();
    }

    /**
     * 请求取消正在运行的任务：只置标志，执行线程在下一个批次之间停止。
     * 取消不是即时的：标志只在每批开头检查，若此时任务正处于可恢复错误的重试阶梯（最长约 90 秒），要等它结束才会停止，
     * 界面不应承诺立即取消。
     *
     * @throws ApiException 409 没有正在运行的任务
     */
    public ReplayStatus cancel() {
        ReplayTask task = current.get();
        if (task == null || !task.isRunning()) {
            throw ApiException.conflict("没有正在运行的重放任务");
        }
        task.cancel();
        log.info("Telemetry replay cancellation requested");
        return task.snapshot();
    }

    /**
     * 执行一次重放：按 id 区间分批 XRANGE 读取并处理，每批之间检查取消标志。
     * 区间左闭右开地映射为两端都含的 id 区间：[from-0, (to-1)-最大序号]。
     * 可恢复错误由 {@link Backoff} 原地重试，超过上限后任务置为 FAILED 并记录原因。
     */
    void run(ReplayTask task) {
        String startId = task.from() + "-0";
        String endId = (task.to() - 1) + "-" + MAX_SEQUENCE;
        try {
            while (true) {
                if (task.isCancelled()) {
                    task.finish(ReplayState.CANCELLED, null, clock.millis());
                    log.info("Telemetry replay cancelled: {}", task.snapshot());
                    return;
                }
                String from = startId;
                List<Delivery> batch = backoff.run("replay range", () -> bus.range(from, endId, BATCH_SIZE));
                if (batch.isEmpty()) {
                    break;
                }
                task.addScanned(batch.size());
                log.debug("Replaying {} entries from {}", batch.size(), startId);
                replayBatch(task, batch);
                startId = nextId(batch.getLast().id());
            }
            task.finish(ReplayState.SUCCEEDED, null, clock.millis());
            log.info("Telemetry replay finished: {}", task.snapshot());
        } catch (ProcessingAbortedException e) {
            task.finish(ReplayState.CANCELLED, null, clock.millis());
            log.info("Telemetry replay interrupted, marked as cancelled");
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            task.finish(ReplayState.FAILED, reason, clock.millis());
            log.warn("Telemetry replay failed: {}", reason);
        }
    }

    /**
     * 重放一批条目：逐条解码校验攒成数据点，整批写时序库，再逐条写最新值。
     * 信封损坏、非属性消息与解码失败计入 skipped；全部测点被剔除计入 rejected（不写死信）；
     * 被 InfluxDB 拒绝的数据点（字段类型冲突）计入 skipped；其余计入 written。
     * 部分测点被剔除的消息照常写入已通过的测点，计为 written。
     */
    private void replayBatch(ReplayTask task, List<Delivery> batch) {
        List<TelemetryPoint> points = new ArrayList<>();
        for (Delivery delivery : batch) {
            RawMessage message;
            try {
                message = delivery.message();
            } catch (IllegalArgumentException e) {
                task.addSkipped(1);
                continue;
            }
            if (message.type() != MessageType.PROPERTY) {
                task.addSkipped(1);
                continue;
            }
            try {
                points.add(backoff.run("replay prepare", () -> ingestor.prepare(delivery.id(), message, IngestSource.REPLAY)).point());
            } catch (DecodeException e) {
                if (e.reason() == DeadReason.FIELD_REJECTED) {
                    task.addRejected(1);
                } else {
                    task.addSkipped(1);
                }
            }
        }
        if (points.isEmpty()) {
            return;
        }
        WriteResult result = backoff.run("replay influx write", () -> ingestor.write(points));
        Set<Integer> rejected = result.rejected().stream()
                .map(WriteResult.Rejected::index).collect(Collectors.toSet());
        for (int i = 0; i < points.size(); i++) {
            if (rejected.contains(i)) {
                task.addSkipped(1);
                continue;
            }
            TelemetryPoint point = points.get(i);
            backoff.run("replay latest update", () -> {
                ingestor.updateLatest(point);
                return null;
            });
            task.addWritten(1);
        }
    }

    /**
     * 下一批的起始 id：同一毫秒内序号加一。序号是无符号 64 位，用无符号解析与格式化。
     *
     * @param lastId 本批最后一条的 id，形如 {@code 1760400000000-7}
     */
    private static String nextId(String lastId) {
        int dash = lastId.indexOf('-');
        String millis = lastId.substring(0, dash);
        long sequence = Long.parseUnsignedLong(lastId.substring(dash + 1));
        if (sequence == -1L) {
            // 序号已是无符号最大值，加一会回绕为 0 而重复读取同一批；改为推进到下一毫秒。
            return (Long.parseLong(millis) + 1) + "-0";
        }
        return millis + "-" + Long.toUnsignedString(sequence + 1);
    }
}
