package dev.conduit.transport.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 以持久会话 + 共享订阅接收设备消息，XADD 成功后才回 PUBACK（手动 ACK），保证至少一次投递。
 * 位于 设备 -MQTT-> EMQX -> transport -> Redis Streams 总线 这一段：订阅设备上行 topic 与 EMQX 的
 * $SYS 上下线 topic，经 {@link TopicParser} 解析后组装 RawMessage 发布到总线。
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
    /** 收到 CONNACK 的累计次数：首次为首连，之后均为断线自动重连。 */
    private final AtomicInteger connectCount = new AtomicInteger();

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

    /**
     * 连接 EMQX（MQTT 5，持久会话 cleanStart=false，断线自动重连）并等待首次订阅成功，最长 30 秒。
     * 先注册全局回调再连接，以便接住重连后 broker 推送的积压消息。
     *
     * @throws IllegalStateException 连接失败、订阅失败或超时（已清理资源，transport 进程应启动失败）
     */
    @Override
    public void start() {
        Mqtt5ClientBuilder builder = MqttClient.builder().useMqttVersion5()
                .identifier(props.clientId())
                .serverHost(props.host())
                .serverPort(props.port())
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener(this::onConnected)
                .addDisconnectedListener(this::onDisconnected);
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

    /** 停止：置停止标志、取消订阅重试、有界等待断开连接并关闭投递线程；未 ACK 的消息由 broker 经持久会话重投。 */
    @Override
    public void stop() {
        stopping = true;
        subscribeRetryExecutor.shutdownNow();
        disconnectQuietly();
        deliveryExecutor.shutdownNow();
    }

    /** start() 失败后的清理，动作与 stop() 相同。 */
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
        int n = connectCount.incrementAndGet();
        if (n == 1) {
            log.info("MQTT connected to {}:{} as {}", props.host(), props.port(), props.clientId());
        } else {
            log.info("MQTT reconnected to {}:{} as {} (connection #{})", props.host(), props.port(),
                    props.clientId(), n);
        }
        subscribeWithRetry(subscriber, connected, 0);
    }

    /**
     * 连接断开（含自动重连过程中单次连接尝试失败）时记录来源与原因，便于判断是主动断开还是网络/broker 问题。
     * 运行在事件循环上，只打日志。
     */
    void onDisconnected(MqttClientDisconnectedContext context) {
        Throwable cause = context.getCause();
        log.info("MQTT disconnected from {}:{} (source={}): {}", props.host(), props.port(), context.getSource(),
                cause == null ? "no cause" : cause.getMessage());
    }

    /**
     * 订阅失败时按同一退避策略重试；仅在仍连接且未停止时重试。绝不 disconnect()：
     * 客户端主动断开不会触发自动重连。若连接断开，下一次 CONNACK 会重新订阅。
     * 首次订阅信号仅在 SUBACK 成功时完成。
     */
    void subscribeWithRetry(Supplier<CompletableFuture<?>> subscriber, BooleanSupplier connected, int attempt) {
        subscriber.get().whenComplete((ack, error) -> {
            if (error == null) {
                log.info("MQTT subscriptions established (shared group {})", props.shareGroup());
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

    /** 指数退避：initialBackoff * 2^attempt（指数封顶 5），上限 30 秒。订阅重试与总线发布重试共用。 */
    private Duration backoff(int attempt) {
        Duration delay = initialBackoff.multipliedBy(1L << Math.min(attempt, 5));
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    /** 订阅设备上行 topic（共享订阅，多实例分摊）及 EMQX 的上下线系统 topic（普通订阅），均为 QoS 1。 */
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

    /**
     * 处理一条收到的 MQTT 消息（在单线程投递线程上串行执行，保序）。
     * 无法路由的 topic 与平台自身客户端（ownClientPrefix）的上下线事件直接 ACK 丢弃；
     * 其余组装为 RawMessage 并阻塞重试发布到总线，发布成功后才 ACK。
     */
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
            log.debug("Ignoring presence of platform client {}", t.deviceKey());
            acknowledge(publish);
            return;
        }
        RawMessage message = new RawMessage(t.productKey(), t.deviceKey(), t.type(),
                publish.getPayloadAsBytes(), clock.millis(), Map.of("topic", topic));
        if (isPresence(t.type())) {
            log.debug("MQTT presence event: clientId={} {}", t.deviceKey(),
                    t.type() == MessageType.PRESENCE_CONNECTED ? "connected" : "disconnected");
        } else {
            log.debug("MQTT received: topic={} productKey={} deviceKey={} type={} payloadBytes={}", topic,
                    t.productKey(), t.deviceKey(), t.type(), message.payload().length);
        }
        if (log.isTraceEnabled()) {
            log.trace("MQTT payload of {}: {}", t.deviceKey(), preview(message.payload()));
        }
        if (publishWithRetry(message)) {
            acknowledge(publish);
        }
    }

    /** 取 payload 的前 256 个字符用于 TRACE 日志（按 UTF-8 解码，避免大报文刷屏）。 */
    private static String preview(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        return text.length() <= 256 ? text : text.substring(0, 256) + "...(truncated)";
    }

    /** 内联重试直到成功；停止时放弃（不 ACK，由 broker 经持久会话重投）。 */
    private boolean publishWithRetry(RawMessage message) {
        for (int attempt = 0; !stopping; attempt++) {
            try {
                String id = bus.publish(message);
                log.debug("Published to bus: id={} deviceKey={} type={}", id, message.deviceKey(), message.type());
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

    /** 是否为设备上下线事件（而非业务数据）。 */
    private static boolean isPresence(MessageType type) {
        return type == MessageType.PRESENCE_CONNECTED || type == MessageType.PRESENCE_DISCONNECTED;
    }
}
