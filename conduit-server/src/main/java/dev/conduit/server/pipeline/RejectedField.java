package dev.conduit.server.pipeline;

/**
 * 被物模型校验剔除的单个测点。
 *
 * @param value 报文中的原始值（解码后的类型）
 */
public record RejectedField(String field, Object value, RejectReason reason) {
}
