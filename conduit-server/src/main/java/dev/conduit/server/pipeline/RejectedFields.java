package dev.conduit.server.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 死信记录中的 rejected 字段：[{"field":"Ua","value":"abc","reason":"TYPE_MISMATCH"}]。 */
final class RejectedFields {

    static final String KEY = "rejected";
    static final int MAX_VALUE_LENGTH = 64;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RejectedFields() {
    }

    static Map<String, String> details(List<RejectedField> rejected) {
        List<Map<String, String>> items = rejected.stream().map(r -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("field", r.field());
            item.put("value", truncate(String.valueOf(r.value())));
            item.put("reason", r.reason().name());
            return item;
        }).toList();
        try {
            return Map.of(KEY, MAPPER.writeValueAsString(items));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String truncate(String value) {
        if (value.codePointCount(0, value.length()) <= MAX_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_VALUE_LENGTH));
    }
}
