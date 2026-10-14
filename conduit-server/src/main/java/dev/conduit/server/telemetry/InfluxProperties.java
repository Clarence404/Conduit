package dev.conduit.server.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "conduit.influx")
public record InfluxProperties(String url, String token, String org, String bucket) {

    public InfluxProperties {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("INFLUX_TOKEN must be set (conduit.influx.token is blank)");
        }
    }
}
