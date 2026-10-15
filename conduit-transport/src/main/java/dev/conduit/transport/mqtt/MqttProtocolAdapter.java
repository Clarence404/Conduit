package dev.conduit.transport.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.transport.adapter.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 以持久会话 + 共享订阅接收设备消息，XADD 成功后才回 PUBACK（手动 ACK），保证至少一次投递。
 */
public class MqttProtocolAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(MqttProtocolAdapter.class);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);

    private final MqttTransportProperties props;
    private final MessageBus bus;
    private final Clock clock;
    private final Duration initialBackoff;
    // 单线程串行处理：保序，且阻塞重试不会卡住 Netty 事件循环
    private final ExecutorService deliveryExecutor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("mqtt-delivery").daemon().factory());
    private final ScheduledExecutorService subscribeRetryExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("mqtt-subscribe-retry").daemon().factory());
    /** 仅在某次订阅真正收到成功 SUBACK 时完成；start() 据此判断就绪。 */
    final CompletableFuture<Void> initialSubscription = new CompletableFuture<>();
    // 测试接缝：默认走真实客户端
    Supplier<CompletableFuture<?>> subscriber = this::subscribe;
    BooleanSupplier connected = () -> this.client != null && this.client.getState().isConnected();
    private volatile boolean stopping;

    private volatile Mqtt5AsyncClient client;

    public MqttProtocolAdapter(MqttTransportProperties props, MessageBus bus, Clock clock) {
        this(props, bus, clock, Duration.ofSeconds(1));
    }

    MqttProtocolAdapter(MqttTransportProperties props, MessageBus bus, Clock clock, Duration initialBackoff) {
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

        try {
            client.connectWith()
                    .cleanStart(false)
                    .sessionExpiryInterval(props.sessionExpirySeconds())
                    .send().join();
            // 订阅由 connected listener 完成；等待首次订阅就绪后才返回
            initialSubscription.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            cleanupAfterFailedStart();
            throw new IllegalStateException("Timed out after 30s waiting for MQTT subscriptions to be established", e);
        } catch (ExecutionException e) {
            cleanupAfterFailedStart();
            throw new IllegalStateException("MQTT subscription failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanupAfterFailedStart();
            throw new IllegalStateException("Interrupted while waiting for MQTT subscriptions", e);
        } catch (RuntimeException e) {
            cleanupAfterFailedStart();
            throw new IllegalStateException("MQTT connect to " + props.host() + ":" + props.port() + " failed", e);
        }

        log.info("MQTT adapter connected to {}:{} as {}", props.host(), props.port(), props.clientId());
    }

    @Override
    public void stop() {
        stopping = true;
        subscribeRetryExecutor.shutdownNow();
        disconnectQuietly();
        deliveryExecutor.shutdownNow();
    }

    private void cleanupAfterFailedStart() {
        stopping = true;
        subscribeRetryExecutor.shutdownNow();
        disconnectQuietly();
        deliveryExecutor.shutdownNow();
    }

    /** 有界等待断开，避免 broker 无响应时无限阻塞。 */
    private void disconnectQuietly() {
        Mqtt5AsyncClient c = client;
        if (c != null && c.getState().isConnected()) {
            try {
                c.disconnect().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                log.warn("MQTT disconnect did not complete cleanly: {}", e.toString());
            }
        }
    }

    /**
     * 每次 CONNACK 都订阅，不看 sessionPresent：首次订阅失败/断线后重连可能返回 sessionPresent=true
     * 但会话里并没有订阅。MQTT 5 重复订阅只是替换原订阅，无害。运行在事件循环上，不可阻塞。
     */
    void onConnected(MqttClientConnectedContext context) {
        subscribeWithRetry(subscriber, connected, 0);
    }

    /**
     * 订阅失败时按同一退避策略重试；仅在仍连接且未停止时重试。绝不 disconnect()：
     * 客户端主动断开不会触发自动重连。若连接断开，下一次 CONNACK 会重新订阅。
     * 首次订阅信号仅在 SUBACK 成功时完成。
     */
    void subscribeWithRetry(Supplier<CompletableFuture<?>> subscriber, BooleanSupplier connected, int attempt) {
        subscriber.get().whenComplete((ack, error) -> {
            if (error == null) {
                log.info("MQTT subscriptions established");
                initialSubscription.complete(null);
                return;
            }
            if (stopping || !connected.getAsBoolean()) {
                log.warn("MQTT subscribe failed and will not be retried (stopping or disconnected): {}",
                        error.getMessage());
                return;
            }
            Duration delay = backoff(attempt);
            log.warn("MQTT subscribe failed (attempt {}), retrying in {} ms: {}",
                    attempt + 1, delay.toMillis(), error.getMessage());
            try {
                subscribeRetryExecutor.schedule(() -> {
                    if (!stopping && connected.getAsBoolean()) {
                        subscribeWithRetry(subscriber, connected, attempt + 1);
                    }
                }, delay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                log.warn("MQTT subscribe retry rejected, adapter is stopping");
            }
        });
    }

    private Duration backoff(int attempt) {
        Duration delay = initialBackoff.multipliedBy(1L << Math.min(attempt, 5));
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
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
                Duration delay = backoff(attempt);
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
