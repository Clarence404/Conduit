package dev.conduit.server.mqttauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EMQX 与平台内部通信的配置（前缀 conduit.internal）。token 与 transportPassword 必填，缺失时构造即抛
 * IllegalStateException，使进程启动失败，避免鉴权接口在无保护状态下运行。
 *
 * @param token             EMQX 调用鉴权回调时携带的内部 token
 * @param transportUsername conduit-transport 连接 EMQX 使用的 MQTT 用户名
 * @param transportPassword conduit-transport 连接 EMQX 使用的 MQTT 密码
 */
@ConfigurationProperties(prefix = "conduit.internal")
public record MqttAuthProperties(String token, String transportUsername, String transportPassword) {

    public MqttAuthProperties {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("CONDUIT_INTERNAL_TOKEN must be set (conduit.internal.token is blank)");
        }
        if (transportPassword == null || transportPassword.isBlank()) {
            throw new IllegalStateException("TRANSPORT_MQTT_PASSWORD must be set (conduit.internal.transport-password is blank)");
        }
    }
}
