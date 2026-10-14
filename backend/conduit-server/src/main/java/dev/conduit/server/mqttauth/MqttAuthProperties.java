package dev.conduit.server.mqttauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "conduit.internal")
public record MqttAuthProperties(String token, String connectorUsername, String connectorPassword) {
}
