package dev.conduit.server.replay;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 重放任务状态快照（三个接口的统一响应）。从未提交过任务时只有 state=NONE，其余字段不输出，
 * 因此用 NON_NULL 序列化。
 *
 * @param scanned  从流中读出的条目数
 * @param written  成功写入时序库的测点消息数
 * @param skipped  解码失败、产品已删除、非属性消息等跳过的条数
 * @param rejected 物模型校验剔除了全部测点的条数（不写死信，仅计数）
 * @param error    FAILED 时的原因，其余状态为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayStatus(ReplayState state, Long from, Long to, Long startedAt, Long finishedAt,
                           Long scanned, Long written, Long skipped, Long rejected, String error) {

    /** 从未提交过任务的状态：{"state":"NONE"}。 */
    public static ReplayStatus none() {
        return new ReplayStatus(ReplayState.NONE, null, null, null, null, null, null, null, null, null);
    }
}
