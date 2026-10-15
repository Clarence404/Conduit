package dev.conduit.server.pipeline.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
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

class FlatJsonDecoderTest {

    static final long NOW = 1_760_400_000_000L;
    final FlatJsonDecoder decoder = new FlatJsonDecoder(new ObjectMapper(),
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
        assertThat(decoder.name()).isEqualTo("flat-json");
        assertThat(decoder.displayName()).isEqualTo("扁平 JSON");
    }

    @Test
    void topLevelKeysExceptTsAndIdArePoints() {
        DecodedPayload p = decode("{\"id\":\"m1\",\"ts\":%d,\"Ua\":220.1,\"Ia\":5,\"on\":true,\"mode\":\"auto\"}"
                .formatted(NOW - 1000));

        assertThat(p.ts()).isEqualTo(NOW - 1000);
        assertThat(p.values()).containsOnlyKeys("Ua", "Ia", "on", "mode")
                .containsEntry("Ua", 220.1).containsEntry("Ia", 5L).containsEntry("on", true);
    }

    @Test
    void missingTimestampFallsBackToReceivedAt() {
        assertThat(decode("{\"Ua\":1}").ts()).isEqualTo(NOW - 10);
    }

    @Test
    void rejectsTimestampOutsideWindow() {
        assertRejected("{\"ts\":%d,\"Ua\":1}".formatted(NOW + Duration.ofMinutes(6).toMillis()),
                DeadReason.TS_OUT_OF_RANGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"id\":\"x\"}",
            "{\"ts\":null}",
            "{\"Ua\":null}",
            "{\"Ua\":{\"v\":1}}",
            "{\"Ua\":[1]}",
            "{\"__x\":1}",
            "{\"\":1}"
    })
    void rejectsInvalidPoints(String json) {
        assertRejected(json, DeadReason.INVALID_PARAMS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1]", "not json", "{\"Ua\":1,\"Ua\":2}", "{\"Ua\":1} x"})
    void rejectsMalformedJson(String json) {
        assertRejected(json, DeadReason.MALFORMED_JSON);
    }
}
