package dev.conduit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** DeviceSimulator 对真实 EMQX 的集成测试：上报数量与时间戳、可选的运行状态 st、连接失败重试后退出。 */
@Testcontainers
class DeviceSimulatorIT {

    @Container
    static final GenericContainer<?> EMQX =
            new GenericContainer<>(DockerImageName.parse("emqx/emqx:5.8.6"))
                    .withExposedPorts(1883)
                    .withCopyToContainer(MountableFile.forClasspathResource("emqx/acl.conf"),
                            "/opt/emqx/etc/acl.conf")
                    .waitingFor(Wait.forLogMessage(".*is running now.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(2));

    private static List<DeviceCredentials> devices(String prefix, int n) {
        List<DeviceCredentials> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(new DeviceCredentials("pk", prefix + i, "secret"));
        }
        return list;
    }

    /** 订阅所有上报并收集 payload 文本。 */
    private static Mqtt5BlockingClient subscribe(String id, List<String> payloads) {
        Mqtt5BlockingClient subscriber = MqttClient.builder().useMqttVersion5().identifier(id)
                .serverHost(EMQX.getHost()).serverPort(EMQX.getMappedPort(1883)).buildBlocking();
        subscriber.connect();
        subscriber.toAsync().subscribeWith().topicFilter("conduit/+/+/up/property").qos(MqttQos.AT_LEAST_ONCE)
                .callback(p -> payloads.add(new String(p.getPayloadAsBytes(), StandardCharsets.UTF_8)))
                .send().join();
        return subscriber;
    }

    @Test
    void publishesAllMessagesWithStrictlyIncreasingTimestampsPerDevice() throws Exception {
        List<String> payloads = new CopyOnWriteArrayList<>();
        Mqtt5BlockingClient subscriber = subscribe("it-subscriber", payloads);

        new DeviceSimulator(EMQX.getHost(), EMQX.getMappedPort(1883), Duration.ofMillis(50), 5,
                Duration.ofSeconds(30)).run(devices("dk-", 3));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(payloads).hasSize(15));
        ObjectMapper mapper = new ObjectMapper();
        Map<String, List<Long>> tsByDevice = new HashMap<>();
        for (String payload : payloads) {
            JsonNode json = mapper.readTree(payload);
            assertThat(json.get("params").has(OperatingState.IDENTIFIER)).as("st is opt-in").isFalse();
            String id = json.get("id").asText();
            tsByDevice.computeIfAbsent(id.substring(0, id.lastIndexOf('-')), k -> new ArrayList<>())
                    .add(json.get("ts").asLong());
        }
        assertThat(tsByDevice).hasSize(3);
        tsByDevice.values().forEach(list -> {
            assertThat(list).hasSize(5);
            for (int i = 1; i < list.size(); i++) {
                assertThat(list.get(i)).isGreaterThan(list.get(i - 1));
            }
        });
        subscriber.disconnect();
    }

    @Test
    void publishesTheOperatingStateWhenEnabled() throws Exception {
        List<String> payloads = new CopyOnWriteArrayList<>();
        Mqtt5BlockingClient subscriber = subscribe("it-subscriber-st", payloads);

        new DeviceSimulator(EMQX.getHost(), EMQX.getMappedPort(1883), Duration.ofMillis(50), 2,
                Duration.ofSeconds(30), PayloadFormat.STANDARD, true).run(devices("st-", 2));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(payloads).hasSize(4));
        ObjectMapper mapper = new ObjectMapper();
        for (String payload : payloads) {
            JsonNode json = mapper.readTree(payload);
            String deviceKey = json.get("id").asText().substring(0, json.get("id").asText().lastIndexOf('-'));
            JsonNode st = json.get("params").get(OperatingState.IDENTIFIER);
            assertThat(st.isIntegralNumber()).isTrue();
            assertThat(st.asLong()).isEqualTo(OperatingState.at(deviceKey, json.get("ts").asLong()));
        }
        subscriber.disconnect();
    }

    @Test
    void eachSimulatedDeviceUsesItsOwnFormatAndReadings() throws Exception {
        List<String> payloads = new CopyOnWriteArrayList<>();
        Mqtt5BlockingClient subscriber = subscribe("it-subscriber-mixed", payloads);
        List<SimulatedDevice> devices = List.of(
                new SimulatedDevice(new DeviceCredentials("pk", "mixed-flat", "secret"), PayloadFormat.FLAT,
                        (ts, seconds) -> new LinkedHashMap<>(Map.of("Ppv", 0.0))),
                new SimulatedDevice(new DeviceCredentials("pk", "mixed-std", "secret"), PayloadFormat.STANDARD,
                        (ts, seconds) -> new LinkedHashMap<>(Map.of("flow", 12.5))));

        new DeviceSimulator(EMQX.getHost(), EMQX.getMappedPort(1883), Duration.ofMillis(50), 2,
                Duration.ofSeconds(30)).runDevices(devices);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(payloads).hasSize(4));
        ObjectMapper mapper = new ObjectMapper();
        for (String payload : payloads) {
            JsonNode json = mapper.readTree(payload);
            if (json.get("id").asText().startsWith("mixed-flat-")) {
                assertThat(json.get("Ppv").asDouble()).isZero();
                assertThat(json.has("params")).isFalse();
            } else {
                assertThat(json.get("params").get("flow").asDouble()).isEqualTo(12.5);
            }
        }
        subscriber.disconnect();
    }

    @Test
    void failedConnectRetriesThenThrowsAndLeavesNoSimulatorThreads() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ConnectRetry fastRetry = new ConnectRetry(3, Duration.ofMillis(100), d -> Thread.sleep(d.toMillis()));

        assertThatThrownBy(() -> new DeviceSimulator("127.0.0.1", closedPort, Duration.ofMillis(50), 5,
                Duration.ofSeconds(30)).withConnectRetry(fastRetry).run(devices("dk-", 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after 3 attempts");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(Thread.getAllStackTraces().keySet())
                        .noneMatch(t -> t.getName().startsWith("simulator-") && t.isAlive()));
    }
}
