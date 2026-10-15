package dev.conduit.server.pipeline;

/**
 * 单个测点被剔除的原因（V0.2-A spec 4.2），由 {@link FieldValidator} 产生，
 * 写入 FIELD_REJECTED 死信记录并作为 conduit.pipeline.rejected_fields 指标的标签。
 * UNDEFINED：物模型中无此测点；TYPE_MISMATCH：类型不符；OUT_OF_RANGE：超出最小/最大值；
 * TOO_LONG：文本超长；ENUM_VALUE：不在枚举取值内。
 */
public enum RejectReason {
    UNDEFINED,
    TYPE_MISMATCH,
    OUT_OF_RANGE,
    TOO_LONG,
    ENUM_VALUE
}
