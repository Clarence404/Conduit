package dev.conduit.connector.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.MessageBusProperties;
import dev.conduit.common.bus.RedisStreamMessageBus;
import dev.conduit.common.message.MessageType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class MqttProtocolAdapterIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2")).withExposedPorts(6379);

    @Container
    static final GenericContainer<?> EMQX =
            new GenericContainer<>(DockerImageName.parse("emqx/emqx:5.8.6"))
                    .withExposedPorts(1883)
                    .withCopyToContainer(MountableFile.forClasspathResource("emqx/acl.conf"),
                            "/opt/emqx/etc/acl.conf")
                    .withEnv("EMQX_SYS_TOPICS__SYS_EVENT_MESSAGES__CLIENT_CONNECTED", "true")
                    .withEnv("EMQX_SYS_TOPICS__SYS_EVENT_MESSAGES__CLIENT_DISCONNECTED", "true")
                    .waitingFor(Wait.forLogMessage(".*is running now.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(2));

    static final String GROUP = "it";
    static final long NOW = 1_760_400_000_000L;

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;

    MessageBus bus;
    MqttProtocolAdapter adapter;
    final List<Mqtt5BlockingClient> devices = new ArrayList<>();

    @BeforeAll
    static void connectRedis() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void closeRedis() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        bus = new RedisStreamMessageBus(redis, new MessageBusProperties("it:up", "it:dead", 1000, 1000));
        bus.ensureGroup(GROUP);

        var props = new MqttConnectorProperties(EMQX.getHost(), EMQX.getMappedPort(1883),
                "conduit-connector-it", null, null, "conduit", 300, "conduit-");
        adapter = new MqttProtocolAdapter(props, bus, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        adapter.start();
    }

    @AfterEach
    void tearDown() {
        devices.forEach(d -> {
            if (d.getState().isConnected()) {
                d.disconnect();
            }
        });
        adapter.stop();
    }

    private Mqtt5BlockingClient device(String clientId) {
        Mqtt5BlockingClient client = MqttClient.builder().useMqttVersion5()
                .identifier(clientId)
                .serverHost(EMQX.getHost()).serverPort(EMQX.getMappedPort(1883))
                .buildBlocking();
        client.connect();
        devices.add(client);
        return client;
    }

    private List<Delivery> drain() {
        List<Delivery> all = new ArrayList<>();
        List<Delivery> batch;
        while (!(batch = bus.read(GROUP, "test", 100, Duration.ofMillis(50))).isEmpty()) {
            all.addAll(batch);
        }
        return all;
    }

    @Test
    void forwardsPropertyMessageToBus() {
        byte[] payload = "{\"params\":{\"Ua\":220.1}}".getBytes(StandardCharsets.UTF_8);
        device("dk-prop").publishWith().topic("conduit/pk1/dk-prop/up/property")
                .qos(MqttQos.AT_LEAST_ONCE).payload(payload).send();

        List<Delivery> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            received.addAll(drain());
            assertThat(received).anySatisfy(d -> assertThat(d.message().type()).isEqualTo(MessageType.PROPERTY));
        });

        var message = received.stream().map(Delivery::message)
                .filter(m -> m.type() == MessageType.PROPERTY).findFirst().orElseThrow();
        assertThat(message.productKey()).isEqualTo("pk1");
        assertThat(message.deviceKey()).isEqualTo("dk-prop");
        assertThat(message.payload()).containsExactly(payload);
        assertThat(message.receivedAt()).isEqualTo(NOW);
        assertThat(message.attributes()).containsEntry("topic", "conduit/pk1/dk-prop/up/property");
    }

    @Test
    void forwardsPresenceEventsButIgnoresOwnClient() {
        Mqtt5BlockingClient client = device("dk-presence");
        client.disconnect();

        List<Delivery> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            received.addAll(drain());
            assertThat(received).anySatisfy(d -> {
                assertThat(d.message().deviceKey()).isEqualTo("dk-presence");
                assertThat(d.message().type()).isEqualTo(MessageType.PRESENCE_DISCONNECTED);
            });
        });

        assertThat(received).anySatisfy(d -> {
            assertThat(d.message().deviceKey()).isEqualTo("dk-presence");
            assertThat(d.message().type()).isEqualTo(MessageType.PRESENCE_CONNECTED);
        });
        assertThat(received).noneSatisfy(d ->
                assertThat(d.message().deviceKey()).startsWith("conduit-"));
    }

    @Test
    void dropsUnroutableTopicWithoutBlocking() {
        Mqtt5BlockingClient client = device("dk-bad");
        client.publishWith().topic("conduit/pk1/dk-bad/up/unknown").qos(MqttQos.AT_LEAST_ONCE)
                .payload(new byte[]{1}).send();
        client.publishWith().topic("conduit/pk1/dk-bad/up/property").qos(MqttQos.AT_LEAST_ONCE)
                .payload(new byte[]{2}).send();

        List<Delivery> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            received.addAll(drain());
            assertThat(received).anySatisfy(d -> assertThat(d.message().type()).isEqualTo(MessageType.PROPERTY));
        });
        assertThat(received).noneSatisfy(d ->
                assertThat(d.message().attributes().get("topic")).endsWith("/up/unknown"));
    }
}
