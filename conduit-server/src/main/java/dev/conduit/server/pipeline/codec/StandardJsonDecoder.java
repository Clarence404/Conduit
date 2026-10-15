package dev.conduit.server.pipeline.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** V0.1 标准格式：{"id":..., "ts":毫秒, "params":{测点: 数值|字符串|布尔}}。 */
@Component
public class StandardJsonDecoder implements PayloadDecoder {

    public static final String NAME = "standard-json";

    private final ObjectReader reader;
    private final TimestampPolicy timestamps;

    public StandardJsonDecoder(ObjectMapper objectMapper, TimestampPolicy timestamps) {
        this.reader = JsonPayloads.strictReader(objectMapper);
        this.timestamps = timestamps;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "标准 JSON";
    }

    @Override
    public DecodedPayload decode(DecodeContext ctx, byte[] payload) {
        JsonNode root = JsonPayloads.readObject(reader, payload);
        long ts = timestamps.resolve(root.get("ts"), ctx.receivedAt());
        JsonNode params = root.get("params");
        if (params == null || !params.isObject() || params.isEmpty()) {
            throw new DecodeException(DeadReason.INVALID_PARAMS, "params must be a non-empty object");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : params.properties()) {
            String name = JsonPayloads.checkName(field.getKey());
            values.put(name, JsonPayloads.rawValue(name, field.getValue()));
        }
        return new DecodedPayload(ts, values);
    }
}
