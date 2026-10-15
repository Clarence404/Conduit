package dev.conduit.server.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** InfluxTime 的单元测试：查询参数格式与返回时间的解析。 */
class InfluxTimeTest {

    static final long T = 1760400000000L; // 2025-10-14T00:00:00Z

    @Test
    void formatsParamsAsIsoUtcWithMillis() {
        assertThat(InfluxTime.iso(0)).isEqualTo("1970-01-01T00:00:00.000Z");
        assertThat(InfluxTime.iso(T)).isEqualTo("2025-10-14T00:00:00.000Z");
        assertThat(InfluxTime.iso(T + 123)).isEqualTo("2025-10-14T00:00:00.123Z");
    }

    @Test
    void parsesZoneLessResultTimesAsUtc() {
        assertThat(InfluxTime.epochMillis("2025-10-14T00:05:00")).isEqualTo(T + 300_000);
        assertThat(InfluxTime.epochMillis("2025-10-14T00:05:00.123456789")).isEqualTo(T + 300_123);
    }

    @Test
    void acceptsExplicitZones() {
        assertThat(InfluxTime.epochMillis("2025-10-14T00:05:00Z")).isEqualTo(T + 300_000);
        assertThat(InfluxTime.epochMillis("2025-10-14T08:05:00+08:00")).isEqualTo(T + 300_000);
    }

    @Test
    void rejectsValuesThatAreNotTimes() {
        assertThatThrownBy(() -> InfluxTime.epochMillis(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InfluxTime.epochMillis(1760400000000L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InfluxTime.epochMillis("yesterday")).isInstanceOf(IllegalArgumentException.class);
    }
}
