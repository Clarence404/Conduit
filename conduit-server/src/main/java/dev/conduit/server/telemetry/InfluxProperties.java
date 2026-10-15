package dev.conduit.server.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "conduit.influx")
public record InfluxProperties(String url,
                               String token,
                               @DefaultValue("telemetry") String database,
                               @DefaultValue("30d") String retention) {

    public InfluxProperties {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("INFLUX_TOKEN must be set (conduit.influx.token is blank)");
        }
    }
}
