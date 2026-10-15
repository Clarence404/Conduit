package dev.conduit.server.presence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 处理 EMQX $SYS 上下线事件：以事件自带时间为准更新在线状态。 */
@Component
public class PresenceHandler {

    private static final Logger log = LoggerFactory.getLogger(PresenceHandler.class);

    private final ObjectMapper objectMapper;
    private final PresenceStore store;

    public PresenceHandler(ObjectMapper objectMapper, PresenceStore store) {
        this.objectMapper = objectMapper;
        this.store = store;
    }

    /**
     * 解析 PRESENCE_CONNECTED / PRESENCE_DISCONNECTED 消息的 JSON 并更新在线状态。
     * 事件时间取 connected_at / disconnected_at，缺失或非数字时退回平台接收时间。
     *
     * @throws DecodeException payload 不是合法 JSON 对象（原因 MALFORMED_JSON，由流水线送入死信）
     */
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
        // 是否真正写入由 PresenceStore 记录（DEBUG）；这里只在 TRACE 留解析出的事件细节
        log.trace("Presence event parsed: deviceKey={} online={} eventTime={} clientIp={}", message.deviceKey(),
                online, eventTime, clientIp);
        store.apply(message.deviceKey(), online, eventTime, clientIp);
    }
}
