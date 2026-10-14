package dev.conduit.server.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解码 Conduit 标准属性格式：{"id":..., "ts":毫秒, "params":{测点: 数值|字符串|布尔}}。
 * 数值统一转为 double，避免同一测点先报整数后报小数在 InfluxDB 中产生类型冲突。
 */
@Component
public class PropertyDecoder {

    static final Duration MAX_PAST = Duration.ofDays(30);
    static final Duration MAX_FUTURE = Duration.ofMinutes(5);

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PropertyDecoder(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public DecodedProperty decode(byte[] payload, long receivedAt) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (IOException e) {
            throw new DecodeException(DeadReason.MALFORMED_JSON, e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new DecodeException(DeadReason.MALFORMED_JSON, "payload is not a JSON object");
        }
        return new DecodedProperty(timestamp(root.get("ts"), receivedAt), params(root.get("params")));
    }

    private long timestamp(JsonNode node, long receivedAt) {
        if (node == null || node.isNull()) {
            return receivedAt;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new DecodeException(DeadReason.TS_OUT_OF_RANGE, "ts must be integer milliseconds");
        }
        long ts = node.asLong();
        long now = clock.millis();
        if (ts < now - MAX_PAST.toMillis() || ts > now + MAX_FUTURE.toMillis()) {
            throw new DecodeException(DeadReason.TS_OUT_OF_RANGE, "ts " + ts + " outside allowed window");
        }
        return ts;
    }

    private static Map<String, Object> params(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new DecodeException(DeadReason.INVALID_PARAMS, "params must be a non-empty object");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            if (name.isBlank() || name.startsWith("__")) {
                throw new DecodeException(DeadReason.INVALID_PARAMS, "invalid field name '" + name + "'");
            }
            JsonNode v = field.getValue();
            Object value;
            if (v.isNumber()) {
                value = v.doubleValue();
            } else if (v.isTextual()) {
                value = v.textValue();
            } else if (v.isBoolean()) {
                value = v.booleanValue();
            } else {
                throw new DecodeException(DeadReason.INVALID_PARAMS, "unsupported value type for '" + name + "'");
            }
            values.put(name, value);
        }
        return values;
    }
}
