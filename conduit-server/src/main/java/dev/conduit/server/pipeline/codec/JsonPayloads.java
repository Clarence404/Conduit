package dev.conduit.server.pipeline.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;

import java.io.IOException;

/** JSON 类解码器（standard-json、flat-json）共用的解析与取值规则；仅包内使用。 */
final class JsonPayloads {

    private JsonPayloads() {
    }

    /** 拒绝重复键与尾随字符。 */
    static ObjectReader strictReader(ObjectMapper mapper) {
        return mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * 把报文解析为 JSON 对象节点。
     *
     * @throws DecodeException MALFORMED_JSON：语法错误、重复键、尾随字符，或根节点不是对象
     */
    static JsonNode readObject(ObjectReader reader, byte[] payload) {
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
        return root;
    }

    /** 测点名不能为空、不能以 __ 开头（平台保留）。 */
    static String checkName(String name) {
        if (name.isBlank() || name.startsWith("__")) {
            throw new DecodeException(DeadReason.INVALID_PARAMS, "invalid field name '" + name + "'");
        }
        return name;
    }

    /** 保留原始类型：Long / BigInteger / Double（可能非有限，由校验决定）/ String / Boolean。 */
    static Object rawValue(String name, JsonNode v) {
        if (v.isIntegralNumber()) {
            return v.canConvertToLong() ? (Object) v.longValue() : v.bigIntegerValue();
        }
        if (v.isFloatingPointNumber()) {
            return v.doubleValue();
        }
        if (v.isTextual()) {
            return v.textValue();
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        throw new DecodeException(DeadReason.INVALID_PARAMS, "unsupported value type for '" + name + "'");
    }
}
