package dev.conduit.common.bus;

import dev.conduit.common.message.RawMessage;
import dev.conduit.common.message.RawMessageCodec;

import java.util.Map;

/**
 * 从总线取到的一次投递。保留原始字段，便于 envelope 损坏时仍能整条送进死信。
 */
public record Delivery(String id, Map<String, String> fields, long deliveryCount) {

    public Delivery {
        fields = Map.copyOf(fields);
    }

    /**
     * 解码 envelope。deliveryCount 语义见 {@link MessageBus}。
     *
     * @throws IllegalArgumentException envelope 字段缺失或非法（调用方应据此送入死信）
     */
    public RawMessage message() {
        return RawMessageCodec.fromFields(fields);
    }
}
