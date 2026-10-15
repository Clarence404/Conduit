package dev.conduit.server.pipeline;

import java.util.Map;

/**
 * 消息本身有问题、重试也不会成功的错误，处理方式是进死信；details 会写入死信记录。
 * 由解码器、物模型校验与 {@link PipelineProcessor} 的身份检查抛出，在 PipelineProcessor 中捕获后记死信并 ACK。
 */
public class DecodeException extends RuntimeException {

    private final DeadReason reason;
    private final Map<String, String> details;

    public DecodeException(DeadReason reason, String message) {
        this(reason, message, Map.of());
    }

    public DecodeException(DeadReason reason, String message, Map<String, String> details) {
        super(message);
        this.reason = reason;
        this.details = Map.copyOf(details);
    }

    public DeadReason reason() {
        return reason;
    }

    public Map<String, String> details() {
        return details;
    }
}
