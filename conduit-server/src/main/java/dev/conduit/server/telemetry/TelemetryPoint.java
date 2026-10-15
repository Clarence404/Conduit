package dev.conduit.server.telemetry;

import java.util.Map;

/**
 * 一条待写入 InfluxDB 的遥测点：一次上报解码、校验后的结果。tenantId/productKey/deviceKey 成为 tag，fields 为测点标识到值的映射，每个测点一列。
 * @param ts 设备时间戳（毫秒）
 */
public record TelemetryPoint(long tenantId, String productKey, String deviceKey, long ts, Map<String, Object> fields) {
}
