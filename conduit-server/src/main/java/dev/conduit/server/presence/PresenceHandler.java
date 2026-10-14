package dev.conduit.server.presence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 处理 EMQX $SYS 上下线事件：以事件自带时间为准更新在线状态。 */
@Component
public class PresenceHandler {

    private final ObjectMapper objectMapper;
    private final PresenceStore store;

    public PresenceHandler(ObjectMapper objectMapper, PresenceStore store) {
        this.objectMapper = objectMapper;
        this.store = store;
    }

    public void handle(RawMessage message) {
        JsonNode root;
        try {
            root = objectMapper.readTree(message.payload());
        } catch (IOException e) {
            throw new DecodeException(DeadReason.MALFORMED_JSON, e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new DecodeException(DeadReason.MALFORMED_JSON, "presence payload is not a JSON object");
        }
        boolean online = message.type() == MessageType.PRESENCE_CONNECTED;
        JsonNode time = root.get(online ? "connected_at" : "disconnected_at");
        long eventTime = time != null && time.canConvertToLong() ? time.asLong() : message.receivedAt();
        String clientIp = root.path("ipaddress").asText("");
        store.apply(message.deviceKey(), online, eventTime, clientIp);
    }
}
