package dev.conduit.server.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfluxPropertiesTest {

    @Test
    void rejectsBlankToken() {
        assertThatThrownBy(() -> new InfluxProperties("http://x", "", "org", "b"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INFLUX_TOKEN");
        assertThatThrownBy(() -> new InfluxProperties("http://x", null, "org", "b"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INFLUX_TOKEN");
    }
}
