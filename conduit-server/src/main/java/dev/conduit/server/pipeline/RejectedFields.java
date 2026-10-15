package dev.conduit.server.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把被剔除的测点序列化为死信记录的 details，仅包内使用。
 * 输出形如 rejected -> [{"field":"Ua","value":"abc","reason":"TYPE_MISMATCH"}]（JSON 字符串）。
 */
final class RejectedFields {

    static final String KEY = "rejected";
    static final int MAX_VALUE_LENGTH = 64;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RejectedFields() {
    }

    /** 生成死信 details：单个键 rejected，值为 JSON 数组；每个 value 先转字符串并按 MAX_VALUE_LENGTH 截断，避免死信记录过大。 */
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

    /** 按 Unicode 码点截断到 MAX_VALUE_LENGTH，不会切开代理对。 */
    static String truncate(String value) {
        if (value.codePointCount(0, value.length()) <= MAX_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_VALUE_LENGTH));
    }
}
