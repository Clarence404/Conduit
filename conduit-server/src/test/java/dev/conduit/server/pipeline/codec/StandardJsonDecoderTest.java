package dev.conduit.server.pipeline.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandardJsonDecoderTest {

    static final long NOW = 1_760_400_000_000L;
    final StandardJsonDecoder decoder = new StandardJsonDecoder(new ObjectMapper(),
            new TimestampPolicy(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)));

    private DecodedPayload decode(String json) {
        return decoder.decode(new DecodeContext("pk", "dk", NOW - 10), json.getBytes(StandardCharsets.UTF_8));
    }

    private void assertRejected(String json, DeadReason reason) {
        assertThatThrownBy(() -> decode(json))
                .isInstanceOf(DecodeException.class)
                .satisfies(e -> assertThat(((DecodeException) e).reason()).isEqualTo(reason));
    }

    @Test
    void namesItself() {
        assertThat(decoder.name()).isEqualTo("standard-json");
        assertThat(decoder.displayName()).isEqualTo("标准 JSON");
    }

    @Test
    void keepsRawValueTypes() {
        DecodedPayload p = decode("""
                {"id":"m1","ts":%d,"params":{"Ua":220,"Ia":5.2,"Pf":220.0,"mode":"auto","alarm":false,
                "big":123456789012345678901234567890}}""".formatted(NOW - 1000));

        assertThat(p.ts()).isEqualTo(NOW - 1000);
        assertThat(p.values()).containsEntry("Ua", 220L).containsEntry("Ia", 5.2).containsEntry("Pf", 220.0)
                .containsEntry("mode", "auto").containsEntry("alarm", false)
                .containsEntry("big", new BigInteger("123456789012345678901234567890"));
        assertThat(p.values().get("Ua")).isInstanceOf(Long.class);
        assertThat(p.values().get("Pf")).isInstanceOf(Double.class);
        assertThat(p.values().keySet()).containsExactly("Ua", "Ia", "Pf", "mode", "alarm", "big");
    }

    @Test
    void leavesNonFiniteNumbersToValidation() {
        assertThat(decode("{\"params\":{\"Ua\":1e999}}").values().get("Ua")).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void missingTimestampFallsBackToReceivedAt() {
        assertThat(decode("{\"params\":{\"Ua\":1}}").ts()).isEqualTo(NOW - 10);
    }

    @Test
    void rejectsTimestampOutsideWindow() {
        assertRejected("{\"ts\":%d,\"params\":{\"Ua\":1}}".formatted(NOW - Duration.ofDays(31).toMillis()),
                DeadReason.TS_OUT_OF_RANGE);
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
            "{\"params\":{\" \":1}}"
    })
    void rejectsInvalidParams(String json) {
        assertRejected(json, DeadReason.INVALID_PARAMS);
    }

    @Test
    void rejectsDuplicateKeysAndTrailingTokens() {
        assertRejected("{\"params\":{\"Ua\":1,\"Ua\":\"x\"}}", DeadReason.MALFORMED_JSON);
        assertRejected("{\"params\":{\"Ua\":1}} xyz", DeadReason.MALFORMED_JSON);
    }

    @Test
    void malformedJsonErrorDoesNotEchoPayload() {
        String marker = "SECRET-MARKER-123";
        for (String json : new String[]{
                "{\"params\":{\"Ua\":1,\"note\":\"" + marker,
                "{\"params\":{\"Ua\":1}, \"x\": " + marker + "}",
                "{\"params\":{\"Ua\":1,\"note\":\"" + marker + "\"}, ]"}) {
            assertThatThrownBy(() -> decode(json))
                    .isInstanceOf(DecodeException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(marker));
        }
    }
}
