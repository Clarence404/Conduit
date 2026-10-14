package dev.conduit.server.telemetry;

import java.util.Map;

public record TelemetryPoint(long tenantId, String productKey, String deviceKey, long ts, Map<String, Object> fields) {
}
