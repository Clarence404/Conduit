package dev.conduit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadFactoryTest {

    @Test
    void buildsConduitStandardPayload() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("Ua", 220.1);
        params.put("EPimp", 1234.567);

        JsonNode json = new ObjectMapper().readTree(PayloadFactory.property("m-1", 1_760_400_000_000L, params));

        assertThat(json.get("id").asText()).isEqualTo("m-1");
        assertThat(json.get("ts").asLong()).isEqualTo(1_760_400_000_000L);
        assertThat(json.get("params").get("Ua").asDouble()).isEqualTo(220.1);
        assertThat(json.get("params").get("EPimp").asDouble()).isEqualTo(1234.567);
    }

    @Test
    void buildsFlatPayloadWithPointsAtTopLevel() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("Ua", 220.1);
        params.put("EPimp", 1234.567);

        JsonNode json = new ObjectMapper().readTree(PayloadFactory.flat("m-1", 1_760_400_000_000L, params));

        assertThat(json.get("id").asText()).isEqualTo("m-1");
        assertThat(json.get("ts").asLong()).isEqualTo(1_760_400_000_000L);
        assertThat(json.get("Ua").asDouble()).isEqualTo(220.1);
        assertThat(json.has("params")).isFalse();
    }

    @Test
    void payloadFormatsMapToCodecs() throws Exception {
        assertThat(PayloadFormat.STANDARD.codec()).isEqualTo("standard-json");
        assertThat(PayloadFormat.FLAT.codec()).isEqualTo("flat-json");
        JsonNode flat = new ObjectMapper().readTree(PayloadFormat.FLAT.encode("m", 1L, Map.of("Ua", 1.0)));
        assertThat(flat.get("Ua").asDouble()).isEqualTo(1.0);
        JsonNode standard = new ObjectMapper().readTree(PayloadFormat.STANDARD.encode("m", 1L, Map.of("Ua", 1.0)));
        assertThat(standard.get("params").get("Ua").asDouble()).isEqualTo(1.0);
    }
}
