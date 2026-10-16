package dev.conduit.server.pipeline;

/**
 * 死信原因，写入死信流记录的 reason 字段并作为 conduit.pipeline.dead 指标的标签。
 * 除 MAX_DELIVERY_EXCEEDED（投递超过 maxDeliveries 次）、PROCESSING_ERROR（意外错误）、
 * FIELD_TYPE_CONFLICT（InfluxDB 拒绝写入）外，均由 {@link DecodeException} 携带，表示消息本身有问题、重试无益。
 * FIELD_REJECTED 较特殊：全部测点被拒绝时整条进死信；部分被拒绝时仅另留一条记录，其余测点照常写入并 ACK。
 */
public enum DeadReason {
    MALFORMED_ENVELOPE,
    MALFORMED_JSON,
    INVALID_PARAMS,
    UNKNOWN_DEVICE,
    DEVICE_DISABLED,
    /** 设备所属租户已停用（已连接的设备在停用后继续上报）。 */
    TENANT_DISABLED,
    PRODUCT_MISMATCH,
    TS_OUT_OF_RANGE,
    FIELD_TYPE_CONFLICT,
    UNSUPPORTED_TYPE,
    MAX_DELIVERY_EXCEEDED,
    PROCESSING_ERROR,
    UNKNOWN_CODEC,
    FIELD_REJECTED
}
