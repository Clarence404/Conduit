package dev.conduit.transport.mqtt;

import dev.conduit.common.message.MessageType;

import java.util.Optional;

/**
 * MQTT topic 解析器（纯函数）：把设备上行 topic 与 EMQX 的 $SYS 上下线 topic 映射为 {@link ParsedTopic}，
 * 供 {@link MqttProtocolAdapter} 决定消息的产品、设备与类型。
 */
public final class TopicParser {

    private TopicParser() {
    }

    /**
     * 解析 topic。以 {@code $SYS/} 开头按上下线事件解析，否则按设备上行 topic 解析。
     *
     * @return 解析结果；格式不符、段为空或类型未知时返回 empty（调用方丢弃该消息）
     */
    public static Optional<ParsedTopic> parse(String topic) {
        return topic.startsWith("$SYS/") ? parseSystemTopic(topic) : parseDeviceTopic(topic);
    }

    /** conduit/{productKey}/{deviceKey}/up/{property|event} */
    private static Optional<ParsedTopic> parseDeviceTopic(String topic) {
        String[] p = topic.split("/", -1);
        if (p.length != 5 || !"conduit".equals(p[0]) || !"up".equals(p[3])
                || p[1].isEmpty() || p[2].isEmpty()) {
            return Optional.empty();
        }
        MessageType type = switch (p[4]) {
            case "property" -> MessageType.PROPERTY;
            case "event" -> MessageType.EVENT;
            default -> null;
        };
        return Optional.ofNullable(type).map(t -> new ParsedTopic(p[1], p[2], t));
    }

    /** $SYS/brokers/{node}/clients/{clientid}/{connected|disconnected} */
    private static Optional<ParsedTopic> parseSystemTopic(String topic) {
        String[] p = topic.split("/", -1);
        if (p.length != 6 || !"brokers".equals(p[1]) || !"clients".equals(p[3]) || p[4].isEmpty()) {
            return Optional.empty();
        }
        MessageType type = switch (p[5]) {
            case "connected" -> MessageType.PRESENCE_CONNECTED;
            case "disconnected" -> MessageType.PRESENCE_DISCONNECTED;
            default -> null;
        };
        return Optional.ofNullable(type).map(t -> new ParsedTopic(null, p[4], t));
    }
}
