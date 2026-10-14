package dev.conduit.server.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
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

    private final ObjectReader reader;
    private final Clock clock;

    public PropertyDecoder(ObjectMapper objectMapper, Clock clock) {
        this.reader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = clock;
    }

    public DecodedProperty decode(byte[] payload, long receivedAt) {
        JsonNode root;
        try {
            root = reader.readTree(payload);
        } catch (JsonProcessingException e) {
            // 不使用 getMessage()：它会附带 [Source: ...] 原始报文片段，进而泄漏到死信与日志
            throw new DecodeException(DeadReason.MALFORMED_JSON, e.getOriginalMessage());
        } catch (IOException e) {
            throw new DecodeException(DeadReason.MALFORMED_JSON, "unreadable payload");
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
                double d = v.doubleValue();
                // 超出 double 范围的字面量会变成 ±Infinity，写入 InfluxDB 会失败
                if (!Double.isFinite(d)) {
                    throw new DecodeException(DeadReason.INVALID_PARAMS, "non-finite number for '" + name + "'");
                }
                value = d;
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
