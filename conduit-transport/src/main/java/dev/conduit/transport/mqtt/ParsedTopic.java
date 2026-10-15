package dev.conduit.transport.mqtt;

import dev.conduit.common.message.MessageType;

public record ParsedTopic(String productKey, String deviceKey, MessageType type) {
}
