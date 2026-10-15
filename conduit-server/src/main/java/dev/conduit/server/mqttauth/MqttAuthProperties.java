package dev.conduit.server.mqttauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
