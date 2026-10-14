package dev.conduit.server.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertyDecoderTest {

    static final long NOW = 1_760_400_000_000L;
    final PropertyDecoder decoder = new PropertyDecoder(new ObjectMapper(),
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

    private DecodedProperty decode(String json) {
        return decoder.decode(json.getBytes(StandardCharsets.UTF_8), NOW - 10);
    }

    private void assertRejected(String json, DeadReason reason) {
        assertThatThrownBy(() -> decode(json))
                .isInstanceOf(DecodeException.class)
                .satisfies(e -> assertThat(((DecodeException) e).reason()).isEqualTo(reason));
    }

    @Test
    void decodesParamsAndTimestamp() {
        DecodedProperty p = decode("""
                {"id":"m1","ts":%d,"params":{"Ua":220,"Ia":5.2,"mode":"auto","alarm":false}}""".formatted(NOW - 1000));

        assertThat(p.ts()).isEqualTo(NOW - 1000);
        assertThat(p.params()).containsEntry("Ua", 220.0).containsEntry("Ia", 5.2)
                .containsEntry("mode", "auto").containsEntry("alarm", false);
        assertThat(p.params().get("Ua")).isInstanceOf(Double.class);
    }

    @Test
    void missingTimestampFallsBackToReceivedAt() {
        assertThat(decode("{\"params\":{\"Ua\":1}}").ts()).isEqualTo(NOW - 10);
        assertThat(decode("{\"ts\":null,\"params\":{\"Ua\":1}}").ts()).isEqualTo(NOW - 10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[1,2]", "42", ""})
    void rejectsMalformedJson(String json) {
        assertRejected(json, DeadReason.MALFORMED_JSON);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"params\":{}}",
            "{\"params\":[1]}",
            "{\"params\":\"x\"}",
            "{\"params\":{\"Ua\":null}}",
            "{\"params\":{\"Ua\":{\"nested\":1}}}",
            "{\"params\":{\"Ua\":[1]}}",
            "{\"params\":{\"__last\":1}}",
            "{\"params\":{\"\":1}}",
            "{\"params\":{\"Ua\":1e999}}",
            "{\"params\":{\"Ua\":-1e999}}"
    })
    void rejectsInvalidParams(String json) {
        assertRejected(json, DeadReason.INVALID_PARAMS);
    }

    @Test
    void rejectsIntegerLiteralThatOverflowsDouble() {
        assertRejected("{\"params\":{\"Ua\":" + "9".repeat(400) + "}}", DeadReason.INVALID_PARAMS);
    }

    @Test
    void rejectsDuplicateKeys() {
        assertRejected("{\"params\":{\"Ua\":1,\"Ua\":\"x\"}}", DeadReason.MALFORMED_JSON);
    }

    @Test
    void rejectsTrailingTokens() {
        assertRejected("{\"params\":{\"Ua\":1}} xyz", DeadReason.MALFORMED_JSON);
    }

    @Test
    void malformedJsonErrorDoesNotEchoPayload() {
        String marker = "SECRET-MARKER-123";
        String[] payloads = {
                "{\"params\":{\"Ua\":1,\"note\":\"" + marker,
                "{\"params\":{\"Ua\":1}, \"x\": " + marker + "}",
                "{\"params\":{\"Ua\":1,\"note\":\"" + marker + "\"}, ]"
        };
        for (String json : payloads) {
            assertThatThrownBy(() -> decode(json))
                    .isInstanceOf(DecodeException.class)
                    .satisfies(e -> {
                        assertThat(((DecodeException) e).reason()).isEqualTo(DeadReason.MALFORMED_JSON);
                        assertThat(e.getMessage()).doesNotContain(marker);
                    });
        }
    }

    @Test
    void rejectsTimestampOutsideAllowedWindow() {
        long tooOld = NOW - Duration.ofDays(31).toMillis();
        long tooNew = NOW + Duration.ofMinutes(6).toMillis();
        long seconds = NOW / 1000;

        assertRejected("{\"ts\":%d,\"params\":{\"Ua\":1}}".formatted(tooOld), DeadReason.TS_OUT_OF_RANGE);
        assertRejected("{\"ts\":%d,\"params\":{\"Ua\":1}}".formatted(tooNew), DeadReason.TS_OUT_OF_RANGE);
        assertRejected("{\"ts\":%d,\"params\":{\"Ua\":1}}".formatted(seconds), DeadReason.TS_OUT_OF_RANGE);
        assertRejected("{\"ts\":\"yesterday\",\"params\":{\"Ua\":1}}", DeadReason.TS_OUT_OF_RANGE);
        assertRejected("{\"ts\":1.5,\"params\":{\"Ua\":1}}", DeadReason.TS_OUT_OF_RANGE);
    }

    @Test
    void acceptsTimestampsAtWindowEdges() {
        long oldest = NOW - Duration.ofDays(30).toMillis();
        long newest = NOW + Duration.ofMinutes(5).toMillis();

        assertThat(decode("{\"ts\":%d,\"params\":{\"Ua\":1}}".formatted(oldest)).ts()).isEqualTo(oldest);
        assertThat(decode("{\"ts\":%d,\"params\":{\"Ua\":1}}".formatted(newest)).ts()).isEqualTo(newest);
    }
}
