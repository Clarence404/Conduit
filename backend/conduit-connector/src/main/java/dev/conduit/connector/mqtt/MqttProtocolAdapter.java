package dev.conduit.connector.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.connector.adapter.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 以持久会话 + 共享订阅接收设备消息，XADD 成功后才回 PUBACK（手动 ACK），保证至少一次投递。
 */
public class MqttProtocolAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(MqttProtocolAdapter.class);
    private static final long MAX_RETRY_DELAY_MS = 30_000;

    private final MqttConnectorProperties props;
    private final MessageBus bus;
    private final Clock clock;
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("mqtt-bus-retry").daemon().factory());

    private Mqtt5AsyncClient client;

    public MqttProtocolAdapter(MqttConnectorProperties props, MessageBus bus, Clock clock) {
        this.props = props;
        this.bus = bus;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "mqtt";
    }

    @Override
    public void start() {
        Mqtt5ClientBuilder builder = MqttClient.builder().useMqttVersion5()
                .identifier(props.clientId())
                .serverHost(props.host())
                .serverPort(props.port())
                .automaticReconnectWithDefaultConfig();
        if (props.username() != null && !props.username().isBlank()) {
            builder = builder.simpleAuth()
                    .username(props.username())
                    .password(String.valueOf(props.password()).getBytes(StandardCharsets.UTF_8))
                    .applySimpleAuth();
        }
        client = builder.buildAsync();

        // 先注册全局回调再连接：持久会话重连后 broker 会直接推送积压消息
        client.publishes(MqttGlobalPublishFilter.SUBSCRIBED, this::onPublish, true);

        client.connectWith()
                .cleanStart(false)
                .sessionExpiryInterval(props.sessionExpirySeconds())
                .send().join();

        client.subscribeWith()
                .addSubscription().topicFilter("$share/" + props.shareGroup() + "/conduit/+/+/up/#")
                .qos(MqttQos.AT_LEAST_ONCE).applySubscription()
                // 上下线事件按事件时间幂等处理，多实例重复投递无害，因此不用共享订阅
                .addSubscription().topicFilter("$SYS/brokers/+/clients/+/connected")
                .qos(MqttQos.AT_LEAST_ONCE).applySubscription()
                .addSubscription().topicFilter("$SYS/brokers/+/clients/+/disconnected")
                .qos(MqttQos.AT_LEAST_ONCE).applySubscription()
                .send().join();

        log.info("MQTT adapter connected to {}:{} as {}", props.host(), props.port(), props.clientId());
    }

    @Override
    public void stop() {
        retryExecutor.shutdownNow();
        if (client != null && client.getState().isConnected()) {
            client.disconnect().join();
        }
    }

    void onPublish(Mqtt5Publish publish) {
        String topic = publish.getTopic().toString();
        Optional<ParsedTopic> parsed = TopicParser.parse(topic);
        if (parsed.isEmpty()) {
            log.warn("Dropping unroutable topic {}", topic);
            publish.acknowledge();
            return;
        }
        ParsedTopic t = parsed.get();
        if (isPresence(t.type()) && t.deviceKey().startsWith(props.ownClientPrefix())) {
            publish.acknowledge();
            return;
        }
        RawMessage message = new RawMessage(t.productKey(), t.deviceKey(), t.type(),
                publish.getPayloadAsBytes(), clock.millis(), Map.of("topic", topic));
        publishWithRetry(message, publish, 0);
    }

    private void publishWithRetry(RawMessage message, Mqtt5Publish publish, int attempt) {
        try {
            bus.publish(message);
            publish.acknowledge();
        } catch (RuntimeException e) {
            long delay = Math.min(MAX_RETRY_DELAY_MS, 1000L << Math.min(attempt, 5));
            log.warn("Bus publish failed for device {} (attempt {}), retrying in {} ms: {}",
                    message.deviceKey(), attempt + 1, delay, e.getMessage());
            retryExecutor.schedule(() -> publishWithRetry(message, publish, attempt + 1),
                    delay, TimeUnit.MILLISECONDS);
        }
    }

    private static boolean isPresence(MessageType type) {
        return type == MessageType.PRESENCE_CONNECTED || type == MessageType.PRESENCE_DISCONNECTED;
    }
}
