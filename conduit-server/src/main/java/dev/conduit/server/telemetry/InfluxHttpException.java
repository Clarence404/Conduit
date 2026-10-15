package dev.conduit.server.telemetry;

/** InfluxDB 返回了非 2xx，且不是「部分行被拒」的响应。 */
public class InfluxHttpException extends RuntimeException {

    private static final int MAX_BODY = 300;

    private final int status;

    public InfluxHttpException(int status, String body) {
        super("InfluxDB HTTP " + status + ": " + abbreviate(body));
        this.status = status;
    }

    /**
     * InfluxDB 返回的 HTTP 状态码，调用方据此区分「请求有误」（400）与「服务不可用」。
     */
    public int status() {
        return status;
    }

    /**
     * 截断响应体到 300 字符，避免异常消息过长；null 视为空串。
     */
    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY) + "…";
    }
}
