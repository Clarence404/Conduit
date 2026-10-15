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
 * 扁平 JSON 解码器（codec = flat-json），流水线「解码」步骤的一种实现。
 * 格式：{"ts":毫秒, "Ua":220.1, ...}。顶层 ts 为时间戳，id 忽略，其余键均为测点，至少一个。
 */
@Component
public class FlatJsonDecoder implements PayloadDecoder {

    public static final String NAME = "flat-json";

    private final ObjectReader reader;
    private final TimestampPolicy timestamps;

    public FlatJsonDecoder(ObjectMapper objectMapper, TimestampPolicy timestamps) {
        this.reader = JsonPayloads.strictReader(objectMapper);
        this.timestamps = timestamps;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "扁平 JSON";
    }

    /**
     * 解析顶层对象：ts 经 TimestampPolicy 解析（缺省取 receivedAt），ts 与 id 之外的键作为测点。
     *
     * @throws DecodeException 非法 JSON（MALFORMED_JSON）；测点名非法、值类型不支持、没有任何测点（INVALID_PARAMS）；
     *                         ts 非整数或超出时间窗口（TS_OUT_OF_RANGE）
     */
    @Override
    public DecodedPayload decode(DecodeContext ctx, byte[] payload) {
        JsonNode root = JsonPayloads.readObject(reader, payload);
        long ts = timestamps.resolve(root.get("ts"), ctx.receivedAt());
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            String name = field.getKey();
            if ("ts".equals(name) || "id".equals(name)) {
                continue;
            }
            JsonPayloads.checkName(name);
            values.put(name, JsonPayloads.rawValue(name, field.getValue()));
        }
        if (values.isEmpty()) {
            throw new DecodeException(DeadReason.INVALID_PARAMS, "payload has no fields besides ts and id");
        }
        return new DecodedPayload(ts, values);
    }
}
