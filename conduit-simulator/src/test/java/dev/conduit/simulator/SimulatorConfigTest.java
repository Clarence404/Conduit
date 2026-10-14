package dev.conduit.simulator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatorConfigTest {

    @Test
    void environmentOverridesLocalProperties() {
        Map<String, String> local = Map.of("CONDUIT_ADMIN_PASSWORD", "from-file", "MQTT_HOST", "file-host");
        Map<String, String> env = Map.of("MQTT_HOST", "env-host");

        SimulatorConfig config = SimulatorConfig.fromEnv(SimulatorConfig.merge(local, env));

        assertThat(config.adminPassword()).isEqualTo("from-file");
        assertThat(config.mqttHost()).isEqualTo("env-host");
    }

    @Test
    void appliesDefaultsWhenOnlyPasswordGiven() {
        SimulatorConfig config = SimulatorConfig.fromEnv(Map.of("CONDUIT_ADMIN_PASSWORD", "pw"));

        assertThat(config.apiUrl()).hasToString("http://localhost:8080");
        assertThat(config.productName()).isEqualTo("Demo Meter");
        assertThat(config.deviceCount()).isEqualTo(100);
    }

    @Test
    void requiresAdminPassword() {
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONDUIT_ADMIN_PASSWORD");
    }
}
