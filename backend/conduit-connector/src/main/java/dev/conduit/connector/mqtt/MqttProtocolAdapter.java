package dev.conduit.connector.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientConnectedContext;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.connector.adapter.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 以持久会话 + 共享订阅接收设备消息，XADD 成功后才回 PUBACK（手动 ACK），保证至少一次投递。
 */
public class MqttProtocolAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(MqttProtocolAdapter.class);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);

    private final MqttConnectorProperties props;
    private final MessageBus bus;
    private final Clock clock;
    private final Duration initialBackoff;
    // 单线程串行处理：保序，且阻塞重试不会卡住 Netty 事件循环
    private final ExecutorService deliveryExecutor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("mqtt-delivery").daemon().factory());
    private final CompletableFuture<Void> initialSubscription = new CompletableFuture<>();
    private volatile boolean stopping;

    private Mqtt5AsyncClient client;

    public MqttProtocolAdapter(MqttConnectorProperties props, MessageBus bus, Clock clock) {
        this(props, bus, clock, Duration.ofSeconds(1));
    }

    MqttProtocolAdapter(MqttConnectorProperties props, MessageBus bus, Clock clock, Duration initialBackoff) {
        this.props = props;
        this.bus = bus;
        this.clock = clock;
        this.initialBackoff = initialBackoff;
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
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener(this::onConnected);
        if (props.username() != null && !props.username().isBlank()) {
            byte[] password = props.password() == null
                    ? new byte[0] : props.password().getBytes(StandardCharsets.UTF_8);
            builder = builder.simpleAuth()
                    .username(props.username())
                    .password(password)
                    .applySimpleAuth();
        }
        client = builder.buildAsync();

        // 先注册全局回调再连接：持久会话重连后 broker 会直接推送积压消息
        client.publishes(MqttGlobalPublishFilter.SUBSCRIBED, this::onPublish, deliveryExecutor, true);

        client.connectWith()
                .cleanStart(false)
                .sessionExpiryInterval(props.sessionExpirySeconds())
                .send().join();
        // 订阅由 connected listener 完成；等待首次订阅就绪后才返回
        initialSubscription.join();

        log.info("MQTT adapter connected to {}:{} as {}", props.host(), props.port(), props.clientId());
    }

    @Override
    public void stop() {
        stopping = true;
        deliveryExecutor.shutdownNow();
        if (client != null && client.getState().isConnected()) {
            client.disconnect().join();
        }
    }

    /** 会话丢失（sessionPresent=false，含首次连接）时重新订阅；运行在事件循环上，不可阻塞。 */
    private void onConnected(MqttClientConnectedContext context) {
        boolean sessionPresent = context instanceof Mqtt5ClientConnectedContext c
                && c.getConnAck().isSessionPresent();
        if (sessionPresent) {
            initialSubscription.complete(null);
            return;
        }
        subscribe().whenComplete((ack, error) -> {
            if (error != null) {
                log.error("MQTT subscribe failed", error);
                initialSubscription.completeExceptionally(error);
                // 重连后订阅失败不能静默：断开以触发自动重连并重新订阅
                client.disconnect();
            } else {
                log.info("MQTT subscriptions established");
                initialSubscription.complete(null);
            }
        });
    }

    private CompletableFuture<?> subscribe() {
        return client.subscribeWith()
                .addSubscription().topicFilter("$share/" + props.shareGroup() + "/conduit/+/+/up/#")
                .qos(MqttQos.AT_LEAST_ONCE).applySubscription()
                // 上下线事件按事件时间幂等处理，多实例重复投递无害，因此不用共享订阅
                .addSubscription().topicFilter("$SYS/brokers/+/clients/+/connected")
                .qos(MqttQos.AT_LEAST_ONCE).applySubscription()
                .addSubscription().topicFilter("$SYS/brokers/+/clients/+/disconnected")
                .qos(MqttQos.AT_LEAST_ONCE).applySubscription()
                .send();
    }

    void onPublish(Mqtt5Publish publish) {
        String topic = publish.getTopic().toString();
        Optional<ParsedTopic> parsed = TopicParser.parse(topic);
        if (parsed.isEmpty()) {
            log.warn("Dropping unroutable topic {}", topic);
            acknowledge(publish);
            return;
        }
        ParsedTopic t = parsed.get();
        if (isPresence(t.type()) && t.deviceKey().startsWith(props.ownClientPrefix())) {
            acknowledge(publish);
            return;
        }
        RawMessage message = new RawMessage(t.productKey(), t.deviceKey(), t.type(),
                publish.getPayloadAsBytes(), clock.millis(), Map.of("topic", topic));
        if (publishWithRetry(message)) {
            acknowledge(publish);
        }
    }

    /** 内联重试直到成功；停止时放弃（不 ACK，由 broker 经持久会话重投）。 */
    private boolean publishWithRetry(RawMessage message) {
        for (int attempt = 0; !stopping; attempt++) {
            try {
                bus.publish(message);
                return true;
            } catch (RuntimeException e) {
                Duration delay = initialBackoff.multipliedBy(1L << Math.min(attempt, 5));
                if (delay.compareTo(MAX_RETRY_DELAY) > 0) {
                    delay = MAX_RETRY_DELAY;
                }
                log.warn("Bus publish failed for device {} (attempt {}), retrying in {} ms: {}",
                        message.deviceKey(), attempt + 1, delay.toMillis(), e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /** ACK 失败不能当作总线失败重投：消息已入总线，重复投递由 broker 重发（至少一次）。 */
    private void acknowledge(Mqtt5Publish publish) {
        try {
            publish.acknowledge();
        } catch (RuntimeException e) {
            log.warn("Failed to acknowledge MQTT publish {}: {}", publish.getTopic(), e.getMessage());
        }
    }

    private static boolean isPresence(MessageType type) {
        return type == MessageType.PRESENCE_CONNECTED || type == MessageType.PRESENCE_DISCONNECTED;
    }
}
