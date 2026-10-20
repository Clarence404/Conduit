package dev.conduit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;

/** 解析平台 QoS 0 业务回执，只接受含非空字符串 id 的 JSON 对象。 */
public final class ApplicationAck {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ApplicationAck() {
    }

    /** 返回回执 id；格式错误或 id 无效时返回 empty，调用方保留待确认消息等待下一次重发。 */
    public static Optional<String> id(byte[] payload) {
        try {
            JsonNode root = JSON.readTree(payload);
            JsonNode id = root == null || !root.isObject() ? null : root.get("id");
            if (id == null || !id.isTextual() || id.textValue().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(id.textValue());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
