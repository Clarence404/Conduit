package dev.conduit.e2e;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import dev.conduit.simulator.ConduitApiClient;
import dev.conduit.simulator.DemoProvisioner;
import dev.conduit.simulator.DeviceCredentials;
import dev.conduit.simulator.DeviceSimulator;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    static final Path DEPLOY = Path.of("..", "..", "deploy").toAbsolutePath().normalize();
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
        api.login(env.get("CONDUIT_ADMIN_USERNAME"), env.get("CONDUIT_ADMIN_PASSWORD"));
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
        compose("kill", "conduit-server");
        Thread.sleep(3_000);
        compose("start", "conduit-server");

        sending.get();

        try (InfluxDBClient influx = InfluxDBClientFactory.create("http://" + host + ":" + influxPort,
                env.get("INFLUX_TOKEN").toCharArray(), "conduit", "telemetry")) {
            await().atMost(Duration.ofMinutes(4)).pollInterval(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(countUa(influx, productKey))
                            .isEqualTo((long) DEVICES * MESSAGES_PER_DEVICE));
        }
    }

    private int port(String key, int defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private static long countUa(InfluxDBClient influx, String productKey) {
        String flux = """
                from(bucket: "telemetry")
                  |> range(start: -1h)
                  |> filter(fn: (r) => r._measurement == "property" and r.product_key == "%s" and r._field == "Ua")
                  |> group()
                  |> count()
                """.formatted(productKey);
        return influx.getQueryApi().query(flux, "conduit").stream()
                .flatMap(t -> t.getRecords().stream())
                .mapToLong(r -> ((Number) r.getValue()).longValue())
                .sum();
    }

    private static void compose(String... args) throws Exception {
        List<String> command = new ArrayList<>(
                Arrays.asList(System.getenv().getOrDefault("E2E_COMPOSE", "docker compose").trim().split("\s+")));
        command.add("-f");
        command.add(DEPLOY.resolve("docker-compose.yml").toString());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).inheritIO().start();
        assertThat(process.waitFor()).as("compose %s", String.join(" ", args)).isZero();
    }
}
