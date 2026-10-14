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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

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

    private static List<DeviceCredentials> devices(int n) {
        List<DeviceCredentials> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(new DeviceCredentials("pk", "dk-" + i, "secret"));
        }
        return list;
    }

    @Test
    void publishesAllMessagesWithStrictlyIncreasingTimestampsPerDevice() throws Exception {
        List<String> payloads = new CopyOnWriteArrayList<>();
        Mqtt5BlockingClient subscriber = MqttClient.builder().useMqttVersion5().identifier("it-subscriber")
                .serverHost(EMQX.getHost()).serverPort(EMQX.getMappedPort(1883)).buildBlocking();
        subscriber.connect();
        subscriber.toAsync().subscribeWith().topicFilter("conduit/+/+/up/property").qos(MqttQos.AT_LEAST_ONCE)
                .callback(p -> payloads.add(new String(p.getPayloadAsBytes(), java.nio.charset.StandardCharsets.UTF_8)))
                .send().join();

        new DeviceSimulator(EMQX.getHost(), EMQX.getMappedPort(1883), Duration.ofMillis(50), 5,
                Duration.ofSeconds(30)).run(devices(3));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(payloads).hasSize(15));
        ObjectMapper mapper = new ObjectMapper();
        Map<String, List<Long>> tsByDevice = new HashMap<>();
        for (String payload : payloads) {
            JsonNode json = mapper.readTree(payload);
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
    void failedConnectThrowsPromptlyAndLeavesNoSimulatorThreads() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> new DeviceSimulator("127.0.0.1", closedPort, Duration.ofMillis(50), 5,
                Duration.ofSeconds(30)).run(devices(2)))
                .isInstanceOf(RuntimeException.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(Thread.getAllStackTraces().keySet())
                        .noneMatch(t -> t.getName().startsWith("simulator-") && t.isAlive()));
    }
}
