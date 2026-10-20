package dev.conduit.transport.mqtt;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.Optional;

/**
 * 从属性上报的 JSON 顶层提取可靠上报元数据。只查看 id 与 ts，其他字段直接跳过，避免 transport
 * 承担产品解码和测点校验职责。
 */
final class ReliableUplink {

    static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int MAX_ID_LENGTH = 128;
    private static final JsonFactory JSON = new JsonFactory();

    private ReliableUplink() {
    }

    /**
     * 提取有效的字符串 id，并记录顶层是否出现 ts；非对象、非法 JSON、超限载荷或无效 id 均不启用回执。
     */
    static Optional<Metadata> extract(byte[] payload) {
        if (payload == null || payload.length > MAX_PAYLOAD_BYTES) {
            return Optional.empty();
        }
        try (JsonParser parser = JSON.createParser(payload)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return Optional.empty();
            }
            String id = null;
            boolean hasTimestamp = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    return Optional.empty();
                }
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (value == null) {
                    return Optional.empty();
                }
                if ("id".equals(name)) {
                    id = value == JsonToken.VALUE_STRING ? parser.getText() : null;
                } else if ("ts".equals(name)) {
                    hasTimestamp = true;
                }
                parser.skipChildren();
            }
            if (parser.nextToken() != null || id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) {
                return Optional.empty();
            }
            return Optional.of(new Metadata(id, hasTimestamp));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 顶层可靠上报字段；ts 只记录是否存在，其业务合法性仍由 server 解码器判断。 */
    record Metadata(String id, boolean hasTimestamp) {
    }
}
