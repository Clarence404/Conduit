package dev.conduit.transport.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param username        为空表示匿名连接（仅测试环境）
 * @param ownClientPrefix 平台内部客户端的 clientId 前缀，这些客户端的上下线事件不转发
 */
@ConfigurationProperties(prefix = "conduit.transport.mqtt")
public record MqttTransportProperties(
        @DefaultValue("localhost") String host,
        @DefaultValue("1883") int port,
        @DefaultValue("conduit-transport-1") String clientId,
        String username,
        String password,
        @DefaultValue("conduit") String shareGroup,
        @DefaultValue("3600") long sessionExpirySeconds,
        @DefaultValue("conduit-") String ownClientPrefix) {
}
