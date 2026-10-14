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
}
