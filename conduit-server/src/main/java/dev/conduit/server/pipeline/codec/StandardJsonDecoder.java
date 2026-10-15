package dev.conduit.server.pipeline.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准 JSON 解码器（codec = standard-json），流水线「解码」步骤的一种实现。
 * 格式（V0.1）：{"id":..., "ts":毫秒, "params":{测点: 数值|字符串|布尔}}，测点必须放在 params 内。
 */
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

    /**
     * 解析 ts（缺省取 receivedAt）与 params 对象中的测点；id 不使用。
     *
     * @throws DecodeException 非法 JSON（MALFORMED_JSON）；params 缺失/非对象/为空、测点名或值类型非法（INVALID_PARAMS）；
     *                         ts 非整数或超出时间窗口（TS_OUT_OF_RANGE）
     */
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
