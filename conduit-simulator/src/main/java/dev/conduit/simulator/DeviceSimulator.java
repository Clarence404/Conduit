package dev.conduit.simulator;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult;
import com.hivemq.client.mqtt.mqtt5.message.publish.puback.Mqtt5PubAck;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAck;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAckReasonCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 每台设备一个 MQTT 连接，按固定周期以 QoS1 上报；消息收到平台业务回执后才从内存队列删除，超时原样重发。
 * 各设备的首次上报在一个周期内均匀错开，并有自己的上报格式与读数来源（{@link SimulatedDevice}）。
 */
public class DeviceSimulator {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulator.class);
    private static final long CONNECT_TIMEOUT_MS = 5_000;
    private static final long SESSION_EXPIRY_SECONDS = 86_400;
    static final Duration DEFAULT_COMPLETION_TIMEOUT = Duration.ofMinutes(10);
    static final Duration DEFAULT_ACK_TIMEOUT = Duration.ofSeconds(30);
    static final int DEFAULT_MAX_PENDING = 1000;

    private final String host;
    private final int port;
    private final Duration interval;
    private final int messagesPerDevice;
    private final Duration completionTimeout;
    private final PayloadFormat format;
    private final boolean operatingState;
    private final Duration ackTimeout;
    private final int maxPending;
    private ConnectRetry connectRetry = new ConnectRetry();

    public DeviceSimulator(String host, int port, Duration interval, int messagesPerDevice) {
        this(host, port, interval, messagesPerDevice, DEFAULT_COMPLETION_TIMEOUT, PayloadFormat.STANDARD, false);
    }

    public DeviceSimulator(String host, int port, Duration interval, int messagesPerDevice,
                           Duration completionTimeout) {
        this(host, port, interval, messagesPerDevice, completionTimeout, PayloadFormat.STANDARD, false);
    }

    public DeviceSimulator(String host, int port, Duration interval, int messagesPerDevice,
                           Duration completionTimeout, PayloadFormat format) {
        this(host, port, interval, messagesPerDevice, completionTimeout, format, false);
    }

    /**
     * 完整构造器。format 与 operatingState 只用于 {@link #run(List)}（演示电表）；{@link #runDevices(List)} 按每台设备自己的设置。
     *
     * @param messagesPerDevice 每台设备的上报条数，0 表示一直运行
     * @param operatingState    run(List) 时是否附带运行状态 st；只有产品物模型中 st 为 ENUM 时才应开启（列类型全表共享，
     *                          无物模型的产品会把 st 存成浮点列，与演示产品的整数列冲突）
     */
    public DeviceSimulator(String host, int port, Duration interval, int messagesPerDevice,
                           Duration completionTimeout, PayloadFormat format, boolean operatingState) {
        this(host, port, interval, messagesPerDevice, completionTimeout, format, operatingState,
                DEFAULT_ACK_TIMEOUT, DEFAULT_MAX_PENDING);
    }

    /** 完整构造器，可配置业务回执超时和每台设备的待确认上限。 */
    public DeviceSimulator(String host, int port, Duration interval, int messagesPerDevice,
                           Duration completionTimeout, PayloadFormat format, boolean operatingState,
                           Duration ackTimeout, int maxPending) {
        this.host = host;
        this.port = port;
        this.interval = interval;
        this.messagesPerDevice = messagesPerDevice;
        this.completionTimeout = completionTimeout;
        this.format = format;
        this.operatingState = operatingState;
        if (ackTimeout.isZero() || ackTimeout.isNegative()) {
            throw new IllegalArgumentException("ackTimeout must be positive");
        }
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending must be at least 1");
        }
        this.ackTimeout = ackTimeout;
        this.maxPending = maxPending;
    }

    /** 替换连接重试策略（测试用较短的间隔与次数）。 */
    DeviceSimulator withConnectRetry(ConnectRetry retry) {
        this.connectRetry = retry;
        return this;
    }

    /** 以演示电表读数模拟这些设备（构造时的 format 与 operatingState），语义同 {@link #runDevices(List)}。 */
    public void run(List<DeviceCredentials> devices) throws InterruptedException {
        runDevices(devices.stream().map(d -> SimulatedDevice.demo(d, format, operatingState)).toList());
    }

    /**
     * 连接所有设备并按周期上报，阻塞到结束。messagesPerDevice &gt; 0 时为有限模式：每台发完即结束，
     * 超过 completionTimeout 未收到全部平台业务回执或生成任务失败则抛 IllegalStateException；否则无限运行直到被中断。
     * 每台设备的首次连接按 connectRetry 重试（默认间隔 2 秒、最多 15 次），仍连不上则抛异常；
     * 结束时（含异常）关闭线程池并断开所有连接。
     *
     * @throws IllegalStateException 某台设备重试后仍连不上、超时未确认完毕或上报生成任务失败
     */
    public void runDevices(List<SimulatedDevice> devices) throws InterruptedException {
        boolean finite = messagesPerDevice > 0;
        long intervalMs = interval.toMillis();
        AtomicInteger threadCounter = new AtomicInteger();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "simulator-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        CountDownLatch finished = new CountDownLatch(finite ? devices.size() : 1);
        AtomicInteger failedDevices = new AtomicInteger();
        AtomicInteger doneMessages = new AtomicInteger();
        List<Mqtt5AsyncClient> connected = new ArrayList<>();

        try {
            List<Mqtt5AsyncClient> clients = new ArrayList<>();
            List<PendingReportQueue> queues = new ArrayList<>();
            for (SimulatedDevice device : devices) {
                PendingReportQueue queue = new PendingReportQueue(maxPending);
                AtomicInteger completed = new AtomicInteger();
                queues.add(queue);
                clients.add(connect(device.credentials(), connected, id -> {
                    if (queue.acknowledge(id)) {
                        int totalDone = doneMessages.incrementAndGet();
                        log.debug("Platform ack received: deviceKey={} id={} pending={} totalDone={}",
                                device.credentials().deviceKey(), id, queue.size(), totalDone);
                        if (finite && completed.incrementAndGet() == messagesPerDevice) {
                            finished.countDown();
                        }
                    } else {
                        log.debug("Ignoring unknown or duplicate platform ack: deviceKey={} id={}",
                                device.credentials().deviceKey(), id);
                    }
                }));
            }

            for (int i = 0; i < devices.size(); i++) {
                SimulatedDevice device = devices.get(i);
                DeviceCredentials d = device.credentials();
                Mqtt5AsyncClient client = clients.get(i);
                PendingReportQueue queue = queues.get(i);
                String topic = "conduit/%s/%s/up/property".formatted(d.productKey(), d.deviceKey());
                AtomicInteger sent = new AtomicInteger();
                AtomicLong lastTs = new AtomicLong();
                AtomicBoolean deviceFailed = new AtomicBoolean();
                long initialDelay = intervalMs * i / Math.max(1, devices.size());

                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        if (deviceFailed.get() || (finite && sent.get() >= messagesPerDevice)
                                || !queue.hasCapacity()) {
                            return;
                        }
                        int seq = sent.get() + 1;
                        long ts = Math.max(System.currentTimeMillis(), lastTs.get() + 1);
                        Map<String, Object> reading = device.readings().next(ts, intervalMs / 1000.0);
                        String id = d.deviceKey() + "-" + seq;
                        byte[] payload = device.format().encode(id, ts, reading);
                        long now = System.currentTimeMillis();
                        if (!queue.offer(id, topic, payload, now)) {
                            return;
                        }
                        sent.incrementAndGet();
                        lastTs.set(ts);
                        log.debug("Publishing {} seq={} ts={} bytes={}", d.deviceKey(), seq, ts, payload.length);
                        if (log.isTraceEnabled()) {
                            String body = new String(payload, StandardCharsets.UTF_8);
                            log.trace("Payload of {}: {}", d.deviceKey(),
                                    body.length() <= 256 ? body : body.substring(0, 256) + "...(truncated)");
                        }
                        publish(client, d.deviceKey(), topic, payload);
                    } catch (RuntimeException e) {
                        log.error("Report task failed for {}", d.deviceKey(), e);
                        if (finite && deviceFailed.compareAndSet(false, true)) {
                            failedDevices.incrementAndGet();
                            finished.countDown();
                        }
                    }
                }, initialDelay, intervalMs, TimeUnit.MILLISECONDS);

                long retryScanMs = Math.max(10, Math.min(1000, ackTimeout.toMillis() / 2));
                scheduler.scheduleWithFixedDelay(() -> {
                    for (PendingReportQueue.PendingReport report
                            : queue.claimExpired(System.currentTimeMillis(), ackTimeout)) {
                        log.debug("Platform ack timed out, resending: deviceKey={} id={}", d.deviceKey(), report.id());
                        publish(client, d.deviceKey(), report.topic(), report.payload());
                    }
                }, retryScanMs, retryScanMs, TimeUnit.MILLISECONDS);
            }

            log.info("Simulating {} devices every {} ms", devices.size(), intervalMs);
            if (finite) {
                boolean completedInTime = finished.await(completionTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!completedInTime) {
                    throw new IllegalStateException("Simulator did not finish within " + completionTimeout
                            + ": " + doneMessages.get() + " of " + (long) devices.size() * messagesPerDevice
                            + " messages acknowledged, " + failedDevices.get() + " device tasks failed");
                }
                if (failedDevices.get() > 0) {
                    throw new IllegalStateException(failedDevices.get() + " device report tasks failed");
                }
            } else {
                finished.await();
            }
        } finally {
            scheduler.shutdownNow();
            for (Mqtt5AsyncClient c : connected) {
                disconnectQuietly(c);
            }
        }
    }

    /**
     * 连接一台设备，失败时按 connectRetry 重试。每次尝试新建客户端；失败的客户端立即断开（停止其自动重连），
     * 成功的加入 connected，由 runDevices 结束时统一断开。
     *
     * @throws IllegalStateException 重试次数用尽
     */
    private Mqtt5AsyncClient connect(DeviceCredentials d, List<Mqtt5AsyncClient> connected, Consumer<String> onAck)
            throws InterruptedException {
        return connectRetry.call("device " + d.deviceKey() + " to " + host + ":" + port, () -> {
            CompletableFuture<Void> firstSubscription = new CompletableFuture<>();
            AtomicReference<Mqtt5AsyncClient> reference = new AtomicReference<>();
            Mqtt5AsyncClient client = buildClient(d, reference, firstSubscription, onAck);
            reference.set(client);
            try {
                client.connectWith().cleanStart(false).sessionExpiryInterval(SESSION_EXPIRY_SECONDS).send()
                        .get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                firstSubscription.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                disconnectQuietly(client);
                throw e;
            }
            connected.add(client);
            log.debug("Device {} connected to {}:{}", d.deviceKey(), host, port);
            return client;
        });
    }

    /** 构造一台设备的 MQTT5 客户端；每次连接都安装回执订阅，避免会话丢失后停止接收回执。 */
    private Mqtt5AsyncClient buildClient(DeviceCredentials d, AtomicReference<Mqtt5AsyncClient> reference,
                                         CompletableFuture<Void> firstSubscription, Consumer<String> onAck) {
        String ackTopic = "conduit/%s/%s/down/ack".formatted(d.productKey(), d.deviceKey());
        return MqttClient.builder().useMqttVersion5()
                .identifier(d.deviceKey())
                .serverHost(host).serverPort(port)
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener(context -> {
                    Mqtt5AsyncClient client = reference.get();
                    if (client == null) {
                        firstSubscription.completeExceptionally(
                                new IllegalStateException("MQTT client unavailable while subscribing"));
                        return;
                    }
                    subscribeForAcks(client, ackTopic, d.deviceKey(), onAck).whenComplete((ignored, error) -> {
                        if (error == null) {
                            firstSubscription.complete(null);
                            log.debug("Device {} subscribed to {}", d.deviceKey(), ackTopic);
                        } else {
                            firstSubscription.completeExceptionally(error);
                            log.warn("Device {} could not subscribe to platform acks: {}", d.deviceKey(),
                                    error.getMessage());
                        }
                    });
                })
                .simpleAuth().username(d.deviceKey()).password(d.secret().getBytes(StandardCharsets.UTF_8))
                .applySimpleAuth()
                .buildAsync();
    }

    /** 每次连接（含自动重连）都重新订阅业务回执，避免 broker 丢失会话后静默失去订阅。 */
    private CompletableFuture<Void> subscribeForAcks(Mqtt5AsyncClient client, String topic, String deviceKey,
                                                      Consumer<String> onAck) {
        return client.subscribeWith().topicFilter(topic).qos(MqttQos.AT_MOST_ONCE)
                .callback(publish -> ApplicationAck.id(publish.getPayloadAsBytes()).ifPresentOrElse(onAck,
                        () -> log.debug("Ignoring malformed platform ack for device {}", deviceKey)))
                .send().thenApply(ack -> {
                    requireGranted(ack);
                    return null;
                });
    }

    /** 任一 SUBACK 错误码都视为订阅失败。 */
    private static void requireGranted(Mqtt5SubAck ack) {
        if (ack.getReasonCodes().stream().anyMatch(Mqtt5SubAckReasonCode::isError)) {
            throw new IllegalStateException("MQTT ack subscription rejected: " + ack.getReasonCodes());
        }
    }

    /** 断开客户端；对从未连上的客户端也会停止其自动重连循环。断开本身的失败只记日志。 */
    private static void disconnectQuietly(Mqtt5AsyncClient client) {
        try {
            client.disconnect().join();
        } catch (RuntimeException e) {
            log.debug("Disconnect failed: {}", e.getMessage());
        }
    }

    /** 以 QoS1 发起一次上报；失败不删除待确认副本，由业务回执超时任务再次发送。 */
    private void publish(Mqtt5AsyncClient client, String deviceKey, String topic, byte[] payload) {
        client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(payload).send()
                .whenComplete((result, error) -> {
                    String problem = error != null ? String.valueOf(error.getMessage()) : problemOf(result);
                    if (problem != null) {
                        log.warn("Publish attempt failed for {}: {}; pending message will be retried after {} ms",
                                deviceKey, problem, ackTimeout.toMillis());
                    }
                });
    }

    /** 从发布结果中提取失败原因；成功（含 result 为 null）返回 null。 */
    private static String problemOf(Mqtt5PublishResult result) {
        if (result == null) {
            return null;
        }
        if (result.getError().isPresent()) {
            return String.valueOf(result.getError().get().getMessage());
        }
        if (result instanceof Mqtt5PublishResult.Mqtt5Qos1Result qos1) {
            Mqtt5PubAck ack = qos1.getPubAck();
            if (ack.getReasonCode().isError()) {
                return "PUBACK " + ack.getReasonCode();
            }
        }
        return null;
    }
}
