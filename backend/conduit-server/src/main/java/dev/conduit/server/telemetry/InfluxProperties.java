package dev.conduit.server.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "conduit.influx")
public record InfluxProperties(String url, String token, String org, String bucket) {
}
