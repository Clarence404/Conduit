package dev.conduit.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.simulator.ConduitApiClient;
import dev.conduit.simulator.DemoProvisioner;
import dev.conduit.simulator.DeviceCredentials;
import dev.conduit.simulator.DeviceSimulator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 前置条件：Compose 环境已启动（docker compose -f deploy/docker-compose.yml up -d）。
 * 场景：10 台设备 × 50 条消息，发送过程中 kill 掉 conduit-server 再启动，最终 InfluxDB 恰好 500 条。
 *
 * 环境变量：E2E_HOST（Docker 主机地址，默认 localhost）、E2E_COMPOSE（compose 命令，默认 "docker compose"）。
 * 端口取自 deploy/.env 的 API_PORT / MQTT_PORT / INFLUX_PORT，缺省为 8091 / 1883 / 8096。
 */
class NoDataLossE2EIT {

    static final Path DEPLOY = Path.of("..", "deploy").toAbsolutePath().normalize();
    static final int DEVICES = 10;
    static final int MESSAGES_PER_DEVICE = 50;

    final Map<String, String> env = DotEnv.load(DEPLOY.resolve(".env"));
    final String host = System.getenv().getOrDefault("E2E_HOST", "localhost");

    @Test
    void noDataLostWhenServerIsKilledMidStream() throws Exception {
        int apiPort = port("API_PORT", 8091);
        int mqttPort = port("MQTT_PORT", 1883);
        int influxPort = port("INFLUX_PORT", 8096);

        ConduitApiClient api = new ConduitApiClient(URI.create("http://" + host + ":" + apiPort));
        api.login(DotEnv.require(env, "CONDUIT_ADMIN_USERNAME"), DotEnv.require(env, "CONDUIT_ADMIN_PASSWORD"));
        List<DeviceCredentials> devices = DemoProvisioner.provision(api, "e2e-" + UUID.randomUUID(), DEVICES);
        String productKey = devices.getFirst().productKey();

        DeviceSimulator simulator = new DeviceSimulator(host, mqttPort, Duration.ofMillis(100), MESSAGES_PER_DEVICE);
        CompletableFuture<Void> sending = CompletableFuture.runAsync(() -> {
            try {
                simulator.run(devices);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        Thread.sleep(1_500);
        assertThat(sending.isDone()).as("sender must still be running when the server is killed").isFalse();
        assertThat(countUa(influxPort, productKey)).as("kill must land mid-stream (fewer than all points stored)")
                .isLessThan((long) DEVICES * MESSAGES_PER_DEVICE);
        compose("kill", "conduit-server");
        Thread.sleep(3_000);
        compose("start", "conduit-server");

        sending.get();

        await().atMost(Duration.ofMinutes(4)).pollInterval(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(countUa(influxPort, productKey))
                        .isEqualTo((long) DEVICES * MESSAGES_PER_DEVICE));

        String dead = composeOutput("exec", "-T", "redis", "redis-cli", "XRANGE", "conduit:stream:dead", "-", "+");
        assertThat(dead).as("no message of this run may be dead-lettered").doesNotContain(productKey);

        // 被 kill 的消费者留下的待确认条目要等 PIPELINE_CLAIM_MIN_IDLE 之后才会被认领；
        // 只检查本次运行的 productKey，避免被同时运行的演示模拟器流量干扰
        Duration claimWait = claimMinIdle().plusSeconds(30);
        await().atMost(claimWait).pollInterval(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(pendingEntriesOf(productKey))
                        .as("pending stream entries of this run in group conduit-server").isEmpty());
    }

    /** 读取 deploy/.env 的 PIPELINE_CLAIM_MIN_IDLE（如 60s、2m），缺省 60s。 */
    private Duration claimMinIdle() {
        String value = env.getOrDefault("PIPELINE_CLAIM_MIN_IDLE", "").trim();
        if (value.isEmpty()) {
            return Duration.ofSeconds(60);
        }
        if (value.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
        }
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        return switch (value.charAt(value.length() - 1)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            default -> throw new IllegalStateException("Unsupported PIPELINE_CLAIM_MIN_IDLE: " + value);
        };
    }

    /** 消费组 conduit-server 中仍待确认、且属于 productKey 的条目内容（空串表示没有）。 */
    private String pendingEntriesOf(String productKey) throws Exception {
        // 上限取大，避免积压超过上限时（按 id 从旧到新列出）漏掉本次运行的条目
        String script = "redis-cli XPENDING conduit:stream:up conduit-server - + 100000 | awk 'NR%4==1' "
                + "| while read id; do redis-cli XRANGE conduit:stream:up \"$id\" \"$id\"; done";
        String entries = composeOutput("exec", "-T", "redis", "sh", "-c", script);
        return entries.contains(productKey) ? entries : "";
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /** 本次运行写入的 Ua 点数。Ua 列还不存在时（全新的库）返回 0，而不是让 COUNT 报错。 */
    private long countUa(int influxPort, String productKey) throws Exception {
        JsonNode column = sql(influxPort, "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'property' AND column_name = 'Ua'");
        if (column.isEmpty()) {
            return 0;
        }
        JsonNode rows = sql(influxPort, "SELECT COUNT(\"Ua\") AS n FROM property WHERE product_key = '"
                + productKey + "' AND time >= now() - INTERVAL '1 hour'");
        return rows.get(0).get("n").asLong();
    }

    private JsonNode sql(int influxPort, String query) throws Exception {
        URI uri = URI.create("http://" + host + ":" + influxPort + "/api/v3/query_sql?db=telemetry&format=json&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri)
                        .header("Authorization", "Bearer " + DotEnv.require(env, "INFLUX_TOKEN"))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("InfluxDB query %s: %s", query, response.body()).isEqualTo(200);
        String body = response.body();
        return body == null || body.isBlank() ? JSON.createArrayNode() : JSON.readTree(body);
    }

    private int port(String key, int defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private static void compose(String... args) throws Exception {
        run(false, args);
    }

    private static String composeOutput(String... args) throws Exception {
        return run(true, args);
    }

    private static String run(boolean capture, String... args) throws Exception {
        List<String> command = new ArrayList<>(
                Arrays.asList(System.getenv().getOrDefault("E2E_COMPOSE", "docker compose").trim().split("\\s+")));
        command.add("-f");
        command.add(DEPLOY.resolve("docker-compose.yml").toString());
        command.addAll(Arrays.asList(args));
        String label = String.join(" ", args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
        if (!capture) {
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }
        Process process = builder.start();
        CompletableFuture<String> out = capture
                ? CompletableFuture.supplyAsync(() -> {
                    try {
                        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                : CompletableFuture.completedFuture("");
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("compose " + label + " timed out after 120 s");
        }
        assertThat(process.exitValue()).as("compose %s", label).isZero();
        return out.get(10, TimeUnit.SECONDS);
    }
}
