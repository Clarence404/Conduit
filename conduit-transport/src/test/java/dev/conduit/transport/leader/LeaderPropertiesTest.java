package dev.conduit.transport.leader;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LeaderProperties 单元测试：默认值与 spec 一致、可按配置覆盖、续期间隔不短于 TTL 时拒绝启动。 */
class LeaderPropertiesTest {

    private static LeaderProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("conduit.transport.leader", LeaderProperties.class);
    }

    @Test
    void defaultsMatchTheSpec() {
        LeaderProperties props = bind(Map.of());

        assertThat(props.key()).isEqualTo("conduit:transport:leader");
        assertThat(props.ttl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(props.renewInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.acquireInterval()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void valuesCanBeOverridden() {
        LeaderProperties props = bind(Map.of(
                "conduit.transport.leader.ttl", "6s",
                "conduit.transport.leader.renew-interval", "2s",
                "conduit.transport.leader.acquire-interval", "500ms"));

        assertThat(props.ttl()).isEqualTo(Duration.ofSeconds(6));
        assertThat(props.renewInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.acquireInterval()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void rejectsARenewIntervalThatIsNotShorterThanTheTtl() {
        assertThatThrownBy(() -> new LeaderProperties("k", Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("renew-interval");
    }

    @Test
    void rejectsNonPositiveIntervals() {
        assertThatThrownBy(() -> new LeaderProperties("k", Duration.ofSeconds(15), Duration.ofSeconds(5),
                Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acquire-interval");
    }
}
