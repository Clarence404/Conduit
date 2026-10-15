package dev.conduit.server.pipeline.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimestampPolicyTest {

    static final long NOW = 1_760_400_000_000L;
    final TimestampPolicy policy = new TimestampPolicy(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    final ObjectMapper mapper = new ObjectMapper();

    private JsonNode node(String json) throws Exception {
        return mapper.readTree(json);
    }

    private void assertOutOfRange(JsonNode ts) {
        assertThatThrownBy(() -> policy.resolve(ts, NOW))
                .isInstanceOf(DecodeException.class)
                .satisfies(e -> assertThat(((DecodeException) e).reason()).isEqualTo(DeadReason.TS_OUT_OF_RANGE));
    }

    @Test
    void missingOrNullFallsBackToReceivedAt() throws Exception {
        assertThat(policy.resolve(null, NOW - 10)).isEqualTo(NOW - 10);
        assertThat(policy.resolve(node("null"), NOW - 10)).isEqualTo(NOW - 10);
    }

    @Test
    void acceptsWindowEdges() throws Exception {
        long oldest = NOW - Duration.ofDays(30).toMillis();
        long newest = NOW + Duration.ofMinutes(5).toMillis();

        assertThat(policy.resolve(node(Long.toString(oldest)), NOW)).isEqualTo(oldest);
        assertThat(policy.resolve(node(Long.toString(newest)), NOW)).isEqualTo(newest);
    }

    @Test
    void rejectsOutsideWindowSecondsAndNonIntegers() throws Exception {
        assertOutOfRange(node(Long.toString(NOW - Duration.ofDays(31).toMillis())));
        assertOutOfRange(node(Long.toString(NOW + Duration.ofMinutes(6).toMillis())));
        assertOutOfRange(node(Long.toString(NOW / 1000)));
        assertOutOfRange(node("\"yesterday\""));
        assertOutOfRange(node("1.5"));
        assertOutOfRange(node("9".repeat(30)));
    }
}
