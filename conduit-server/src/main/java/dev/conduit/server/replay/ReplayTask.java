package dev.conduit.server.replay;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次重放任务的运行时状态：时间范围、四项计数、取消标志与结束信息。
 * 执行线程写、请求线程读，因此计数用原子类型、状态字段用 volatile；状态不持久化（spec 4.2）。
 */
class ReplayTask {

    private final long from;
    private final long to;
    private final long startedAt;
    private final AtomicLong scanned = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private volatile ReplayState state = ReplayState.RUNNING;
    private volatile Long finishedAt;
    private volatile String error;

    ReplayTask(long from, long to, long startedAt) {
        this.from = from;
        this.to = to;
        this.startedAt = startedAt;
    }

    long from() {
        return from;
    }

    long to() {
        return to;
    }

    /** 任务是否仍在运行（决定能否提交新任务、能否取消）。 */
    boolean isRunning() {
        return state == ReplayState.RUNNING;
    }

    /** 置取消标志；执行线程在下一个批次之间看到后停止。 */
    void cancel() {
        cancelled.set(true);
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    void addScanned(long n) {
        scanned.addAndGet(n);
    }

    void addWritten(long n) {
        written.addAndGet(n);
    }

    void addSkipped(long n) {
        skipped.addAndGet(n);
    }

    void addRejected(long n) {
        rejected.addAndGet(n);
    }

    /**
     * 结束任务并记录结果；已完成部分的计数保留。
     *
     * @param error FAILED 时的原因，其余状态传 null
     */
    void finish(ReplayState finalState, String error, long finishedAt) {
        this.error = error;
        this.finishedAt = finishedAt;
        this.state = finalState;
    }

    /** 当前状态的快照，供 REST 接口返回。 */
    ReplayStatus snapshot() {
        // finish 先写 finishedAt/error、最后写 state（volatile）。这里先读 state：仍是 RUNNING 就不带结束信息，
        // 已是终态则 finishedAt/error 必然已写入，因此不会出现 RUNNING 与 finishedAt 同时存在的撕裂快照。
        ReplayState s = state;
        boolean done = s != ReplayState.RUNNING;
        return new ReplayStatus(s, from, to, startedAt, done ? finishedAt : null,
                scanned.get(), written.get(), skipped.get(), rejected.get(), done ? error : null);
    }
}
