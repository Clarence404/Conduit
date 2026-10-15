package dev.conduit.server.telemetry;

/** InfluxDB 返回了非 2xx，且不是「部分行被拒」的响应。 */
public class InfluxHttpException extends RuntimeException {

    private static final int MAX_BODY = 300;

    private final int status;

    public InfluxHttpException(int status, String body) {
        super("InfluxDB HTTP " + status + ": " + abbreviate(body));
        this.status = status;
    }

    public int status() {
        return status;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY) + "…";
    }
}
