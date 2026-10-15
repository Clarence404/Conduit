package dev.conduit.transport.mqtt;

import dev.conduit.common.message.MessageType;

/**
 * {@link TopicParser} 的解析结果：从 MQTT topic 得到的路由信息。
 *
 * @param productKey 产品 Key；$SYS 上下线 topic 不含产品，为 null
 * @param deviceKey  设备 Key（设备 topic 中的段，或上下线 topic 中的 clientId）
 * @param type       消息类型
 */
public record ParsedTopic(String productKey, String deviceKey, MessageType type) {
}
