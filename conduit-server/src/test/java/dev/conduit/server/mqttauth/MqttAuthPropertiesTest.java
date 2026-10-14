package dev.conduit.server.mqttauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttAuthPropertiesTest {

    @Test
    void acceptsFullConfig() {
        assertThat(new MqttAuthProperties("tok", "conn", "pw").token()).isEqualTo("tok");
    }

    @Test
    void rejectsBlankInternalToken() {
        assertThatThrownBy(() -> new MqttAuthProperties("", "conn", "pw"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CONDUIT_INTERNAL_TOKEN");
        assertThatThrownBy(() -> new MqttAuthProperties(null, "conn", "pw"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CONDUIT_INTERNAL_TOKEN");
    }

    @Test
    void rejectsBlankConnectorPassword() {
        assertThatThrownBy(() -> new MqttAuthProperties("tok", "conn", "  "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CONNECTOR_MQTT_PASSWORD");
    }
}
