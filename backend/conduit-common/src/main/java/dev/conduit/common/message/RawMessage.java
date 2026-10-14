package dev.conduit.common.message;

import java.util.Map;
import java.util.Objects;

/**
 * 协议无关的原始消息。connector 只负责组装它，业务解析在 server 完成。
 *
 * @param productKey 产品 Key；上下线事件拿不到时为 null
 * @param deviceKey  设备 Key（等于 MQTT clientId）
 * @param payload    原始字节，不做任何解析
 * @param receivedAt 平台接收时间（毫秒）
 * @param attributes 协议相关的附加信息，例如原始 topic
 */
public record RawMessage(String productKey, String deviceKey, MessageType type, byte[] payload,
                         long receivedAt, Map<String, String> attributes) {

    public RawMessage {
        Objects.requireNonNull(deviceKey, "deviceKey");
        Objects.requireNonNull(type, "type");
        payload = payload == null ? new byte[0] : payload;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
