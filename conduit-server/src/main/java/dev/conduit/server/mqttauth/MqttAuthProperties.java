package dev.conduit.server.mqttauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "conduit.internal")
public record MqttAuthProperties(String token, String connectorUsername, String connectorPassword) {

    public MqttAuthProperties {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("CONDUIT_INTERNAL_TOKEN must be set (conduit.internal.token is blank)");
        }
        if (connectorPassword == null || connectorPassword.isBlank()) {
            throw new IllegalStateException("CONNECTOR_MQTT_PASSWORD must be set (conduit.internal.connector-password is blank)");
        }
    }
}
