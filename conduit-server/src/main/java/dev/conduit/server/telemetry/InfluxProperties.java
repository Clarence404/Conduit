package dev.conduit.server.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * InfluxDB 连接配置（前缀 conduit.influx）。
 * @param url 服务地址
 * @param token 访问令牌，必填，缺失则启动失败
 * @param database 库名，默认 telemetry
 * @param retention 保留期，仅在建库时生效，默认 30d
 */
@ConfigurationProperties(prefix = "conduit.influx")
public record InfluxProperties(String url,
                               String token,
                               @DefaultValue("telemetry") String database,
                               @DefaultValue("30d") String retention) {

    /**
     * 紧凑构造器：token 为空直接让应用启动失败，避免带着无效配置运行。
     */
    public InfluxProperties {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("INFLUX_TOKEN must be set (conduit.influx.token is blank)");
        }
    }
}
