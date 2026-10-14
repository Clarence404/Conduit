package dev.conduit.server.pipeline;

/** 消息本身有问题、重试也不会成功的错误，处理方式是进死信。 */
public class DecodeException extends RuntimeException {

    private final DeadReason reason;

    public DecodeException(DeadReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DeadReason reason() {
        return reason;
    }
}
