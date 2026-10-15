package dev.conduit.server.pipeline;

/** 单个测点被剔除的原因（V0.2-A spec 4.2）。 */
public enum RejectReason {
    UNDEFINED,
    TYPE_MISMATCH,
    OUT_OF_RANGE,
    TOO_LONG,
    ENUM_VALUE
}
