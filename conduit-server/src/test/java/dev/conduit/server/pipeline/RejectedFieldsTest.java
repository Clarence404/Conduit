package dev.conduit.server.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RejectedFieldsTest {

    @Test
    void serializesFieldValueAndReason() throws Exception {
        Map<String, String> details = RejectedFields.details(List.of(
                new RejectedField("Ua", "abc", RejectReason.TYPE_MISMATCH),
                new RejectedField("Ib", 999L, RejectReason.OUT_OF_RANGE)));

        JsonNode json = new ObjectMapper().readTree(details.get("rejected"));
        assertThat(details).containsOnlyKeys("rejected");
        assertThat(json.get(0).get("field").asText()).isEqualTo("Ua");
        assertThat(json.get(0).get("value").asText()).isEqualTo("abc");
        assertThat(json.get(0).get("reason").asText()).isEqualTo("TYPE_MISMATCH");
        assertThat(json.get(1).get("value").asText()).isEqualTo("999");
    }

    @Test
    void truncatesValuesTo64CodePointsWithoutSplittingSurrogates() throws Exception {
        String emoji = "😀";
        Map<String, String> details = RejectedFields.details(List.of(
                new RejectedField("t", "x".repeat(100), RejectReason.TOO_LONG),
                new RejectedField("e", emoji.repeat(70), RejectReason.TOO_LONG)));

        JsonNode json = new ObjectMapper().readTree(details.get("rejected"));
        assertThat(json.get(0).get("value").asText()).hasSize(64);
        String e = json.get(1).get("value").asText();
        assertThat(e.codePointCount(0, e.length())).isEqualTo(64);
    }
}
