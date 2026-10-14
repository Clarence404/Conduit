package dev.conduit.server.telemetry;

/** 时序库暂时不可用（或配置错误），调用方应不 ACK、退避后重试。 */
public class TelemetryUnavailableException extends RuntimeException {

    public TelemetryUnavailableException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}
