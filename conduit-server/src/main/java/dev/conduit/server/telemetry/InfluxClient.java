package dev.conduit.server.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * InfluxDB 3 Core HTTP API（v3）的最小客户端。IO 失败抛 UncheckedIOException；非 2xx 抛 InfluxHttpException，
 * 唯一例外是写入时的部分行被拒（400 + partial write JSON），此时返回被拒的行。
 */
public class InfluxClient implements AutoCloseable {

    /** 被拒的一行；lineNumber 从 1 开始，对应提交顺序。 */
    public record LineError(int lineNumber, String message) {
    }

    static final String PARTIAL_WRITE = "partial write of line protocol occurred";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {
    };

    private final String baseUrl;
    private final String authorization;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 构造客户端。baseUrl 末尾的斜杠会被去掉；token 以 Bearer 方式随每个请求发送。连接超时 5 秒，单请求超时 10 秒。
     */
    public InfluxClient(URI baseUrl, String token) {
        String url = baseUrl.toString();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.authorization = "Bearer " + token;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** 写入行协议（accept_partial 默认开启：合格行照常写入）。全部成功返回空列表。 */
    public List<LineError> writeLines(String db, List<String> lines) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        uri("/api/v3/write_lp", "db", db, "precision", "millisecond"))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(String.join("\n", lines), StandardCharsets.UTF_8)));
        int status = response.statusCode();
        if (status == 204 || status == 200) {
            return List.of();
        }
        if (status == 400) {
            List<LineError> errors = partialWriteErrors(response.body());
            if (errors != null) {
                return errors;
            }
        }
        throw new InfluxHttpException(status, response.body());
    }

    /**
     * 执行 SQL 查询（/api/v3/query_sql，JSON 格式），返回行列表（列名 → 值）。
     * 响应体为空时返回空列表；非 200 抛 InfluxHttpException；响应不是预期的 JSON 数组时抛 IllegalStateException。
     * SQL 经 URL 编码放在查询串中，调用方必须自行保证 SQL 已参数化/转义。
     */
    public List<Map<String, Object>> querySql(String db, String sql) {
        return rows(send(HttpRequest.newBuilder(
                uri("/api/v3/query_sql", "db", db, "q", sql, "format", "json")).GET()));
    }

    /**
     * 参数化 SQL 查询（POST /api/v3/query_sql，format=json）。SQL 中用 $name 引用 params 中的值：
     * 字符串参数可直接用于比较；时间参数必须是 {@link InfluxTime#iso(long)} 生成的 ISO-8601 UTC 字符串。
     *
     * @param db     数据库名
     * @param sql    SQL 文本，不得拼接任何请求值
     * @param params 参数名 → 值
     * @return 结果行（列名 → 值），无结果为空列表
     * @throws InfluxHttpException  InfluxDB 返回非 200
     * @throws UncheckedIOException 连接失败、超时等 IO 错误
     */
    public List<Map<String, Object>> querySql(String db, String sql, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("db", db);
        body.put("q", sql);
        body.put("params", params);
        body.put("format", "json");
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return rows(send(HttpRequest.newBuilder(uri("/api/v3/query_sql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))));
    }

    /**
     * 解析查询响应：非 200 抛 InfluxHttpException；空响应体视为无结果；
     * 响应不是预期的 JSON 数组时抛 IllegalStateException。
     */
    private List<Map<String, Object>> rows(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new InfluxHttpException(response.statusCode(), response.body());
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(body, ROWS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unexpected InfluxDB query response: " + e.getOriginalMessage(), e);
        }
    }

    /** 保留期只能在建库时设置；库已存在（409）视为成功。 */
    public void createDatabaseIfMissing(String db, String retention) {
        String body;
        try {
            body = mapper.writeValueAsString(Map.of("db", db, "retention_period", retention));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v3/configure/database"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
        int status = response.statusCode();
        if (status / 100 == 2 || status == 409) {
            return;
        }
        throw new InfluxHttpException(status, response.body());
    }

    /**
     * 关闭底层 HTTP 客户端；由 Spring 在容器销毁时调用。
     */
    @Override
    public void close() {
        http.close();
    }

    /**
     * 解析「部分行被拒」的 400 响应。响应不是该格式（或 JSON 无法解析）时返回 null，由调用方按普通 HTTP 错误处理。
     */
    private List<LineError> partialWriteErrors(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !PARTIAL_WRITE.equals(root.path("error").asText()) || !root.path("data").isArray()) {
                return null;
            }
            List<LineError> errors = new ArrayList<>();
            for (JsonNode item : root.get("data")) {
                errors.add(new LineError(item.path("line_number").asInt(-1), item.path("error_message").asText("")));
            }
            return errors;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 拼接请求地址；query 为键值交替的可变参数，值做 URL 编码。
     */
    private URI uri(String path, String... query) {
        StringBuilder sb = new StringBuilder(baseUrl).append(path);
        for (int i = 0; i < query.length; i += 2) {
            sb.append(i == 0 ? '?' : '&').append(query[i]).append('=')
                    .append(URLEncoder.encode(query[i + 1], StandardCharsets.UTF_8));
        }
        return URI.create(sb.toString());
    }

    /**
     * 发送请求并补上超时与鉴权头。IO 失败抛 UncheckedIOException；线程被中断时恢复中断标志后同样抛 UncheckedIOException。
     */
    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(REQUEST_TIMEOUT).header("Authorization", authorization).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new InterruptedIOException("Interrupted while calling InfluxDB"));
        }
    }
}
