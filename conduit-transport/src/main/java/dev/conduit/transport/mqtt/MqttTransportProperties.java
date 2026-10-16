package dev.conduit.transport.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * transport 连接 EMQX 的 MQTT 配置（前缀 conduit.transport.mqtt）。
 *
 * @param clientId             所有 transport 实例共用的 clientId：EMQX 磁盘持久会话按 clientId 保存离线积压，新主实例接管同一会话
 * @param username             为空表示匿名连接（仅测试环境）
 * @param sessionExpirySeconds 会话保留秒数，与 EMQX 持久会话消息保留期（1 天）一致
 * @param ownClientPrefix      平台内部客户端的 clientId 前缀，这些客户端的上下线事件不转发
 */
@ConfigurationProperties(prefix = "conduit.transport.mqtt")
public record MqttTransportProperties(
        @DefaultValue("localhost") String host,
        @DefaultValue("1883") int port,
        @DefaultValue("conduit-transport") String clientId,
        String username,
        String password,
        @DefaultValue("86400") long sessionExpirySeconds,
        @DefaultValue("conduit-") String ownClientPrefix) {
}
