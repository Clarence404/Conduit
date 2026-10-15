package dev.conduit.server.telemetry;

/**
 * 某个测点的最新值（保存在 Redis，供最新值接口读取）。
 * @param value 值，类型随 JSON 反序列化（数值/布尔/字符串）
 * @param ts 该值的设备时间戳（毫秒）
 */
public record LatestValue(Object value, long ts) {
}
