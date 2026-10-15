package dev.conduit.server.pipeline.codec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecoderRegistryTest {

    private static PayloadDecoder decoder(String name) {
        return new PayloadDecoder() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String displayName() {
                return name.toUpperCase();
            }

            @Override
            public DecodedPayload decode(DecodeContext ctx, byte[] payload) {
                return new DecodedPayload(ctx.receivedAt(), Map.of("x", 1L));
            }
        };
    }

    @Test
    void findsDecodersByNameAndListsThemSorted() {
        DecoderRegistry registry = new DecoderRegistry(List.of(decoder("standard-json"), decoder("flat-json")));

        assertThat(registry.find("flat-json")).map(PayloadDecoder::name).contains("flat-json");
        assertThat(registry.find("nope")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.exists("standard-json")).isTrue();
        assertThat(registry.exists("nope")).isFalse();
        assertThat(registry.all()).extracting(PayloadDecoder::name).containsExactly("flat-json", "standard-json");
    }

    @Test
    void duplicateNamesFailStartup() {
        assertThatThrownBy(() -> new DecoderRegistry(List.of(decoder("a"), decoder("a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'a'");
    }
}
