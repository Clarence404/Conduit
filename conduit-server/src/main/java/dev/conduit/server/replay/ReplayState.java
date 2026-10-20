package dev.conduit.server.replay;

/**
 * 重放任务状态。NONE 表示本进程启动以来从未提交过任务；最近一次完成的状态保留到下一次提交为止。
 */
public enum ReplayState {
    /** 从未提交过任务 */
    NONE,
    /** 正在读取并重放 */
    RUNNING,
    /** 正常跑完整个范围 */
    SUCCEEDED,
    /** 因错误中止，error 字段给出原因 */
    FAILED,
    /** 被取消，已完成部分的统计保留 */
    CANCELLED
}
