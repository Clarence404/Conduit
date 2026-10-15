package dev.conduit.simulator;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult;
import com.hivemq.client.mqtt.mqtt5.message.publish.puback.Mqtt5PubAck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 每台设备一个 MQTT 连接，按固定周期以 QoS1 上报；各设备的首次上报在一个周期内均匀错开。 */
public class DeviceSimulator {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulator.class);
    private static final int MAX_PUBLISH_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MS = 1_000;
    private static final long CONNECT_TIMEOUT_MS = 5_000;
    static final Duration DEFAULT_COMPLETION_TIMEOUT = Duration.ofMinutes(10);

    private final String host;
    private final int port;
    private final Duration interval;
    private final int messagesPerDevice;
    private final Duration completionTimeout;
    private final PayloadFormat format;

    public DeviceSimulator(String host, int port, Duration interval, int messagesPerDevice) {
        this(host, port, interval, messagesPerDevice, DEFAULT_COMPLETION_TIMEOUT, PayloadFormat.STANDARD);
    }

    public DeviceSimulator(String host, int port, Duration interval, int messagesPerDevice,
                           Duration completionTimeout) {
        this(host, port, interval, messagesPerDevice, completionTimeout, PayloadFormat.STANDARD);
    }

    public DeviceSimulator(String host, int port, Duration interval, int messagesPerDevice,
                           Duration completionTimeout, PayloadFormat format) {
        this.host = host;
        this.port = port;
        this.interval = interval;
        this.messagesPerDevice = messagesPerDevice;
        this.completionTimeout = completionTimeout;
        this.format = format;
    }

    public void run(List<DeviceCredentials> devices) throws InterruptedException {
        boolean finite = messagesPerDevice > 0;
        long intervalMs = interval.toMillis();
        AtomicInteger threadCounter = new AtomicInteger();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "simulator-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        CountDownLatch finished = new CountDownLatch(finite ? devices.size() : 1);
        AtomicInteger failedMessages = new AtomicInteger();
        AtomicInteger doneMessages = new AtomicInteger();
        List<Mqtt5AsyncClient> connected = new ArrayList<>();

        try {
            List<Mqtt5AsyncClient> clients = new ArrayList<>();
            for (DeviceCredentials d : devices) {
                Mqtt5AsyncClient client = MqttClient.builder().useMqttVersion5()
                        .identifier(d.deviceKey())
                        .serverHost(host).serverPort(port)
                        .automaticReconnectWithDefaultConfig()
                        .simpleAuth().username(d.deviceKey()).password(d.secret().getBytes(StandardCharsets.UTF_8))
                        .applySimpleAuth()
                        .buildAsync();
                connected.add(client);
                try {
                    client.connect().get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (ExecutionException | TimeoutException e) {
                    throw new IllegalStateException("Cannot connect device " + d.deviceKey() + " to "
                            + host + ":" + port + ": " + e.getMessage(), e);
                }
                clients.add(client);
            }

            for (int i = 0; i < devices.size(); i++) {
                DeviceCredentials d = devices.get(i);
                Mqtt5AsyncClient client = clients.get(i);
                ReadingGenerator generator = new ReadingGenerator(d.deviceKey().hashCode());
                String topic = "conduit/%s/%s/up/property".formatted(d.productKey(), d.deviceKey());
                AtomicInteger sent = new AtomicInteger();
                AtomicInteger completed = new AtomicInteger();
                AtomicLong lastTs = new AtomicLong();
                long initialDelay = intervalMs * i / Math.max(1, devices.size());

                Runnable onMessageDone = () -> {
                    doneMessages.incrementAndGet();
                    if (finite && completed.incrementAndGet() == messagesPerDevice) {
                        finished.countDown();
                    }
                };

                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        if (finite && sent.get() >= messagesPerDevice) {
                            return;
                        }
                        int seq = sent.incrementAndGet();
                        long ts = Math.max(System.currentTimeMillis(), lastTs.get() + 1);
                        lastTs.set(ts);
                        byte[] payload = format.encode(d.deviceKey() + "-" + seq, ts,
                                generator.next(intervalMs / 1000.0));
                        publish(scheduler, client, d.deviceKey(), topic, payload, 1, onMessageDone, failedMessages);
                    } catch (RuntimeException e) {
                        log.error("Report task failed for {}", d.deviceKey(), e);
                        if (finite) {
                            failedMessages.incrementAndGet();
                            onMessageDone.run();
                        }
                    }
                }, initialDelay, intervalMs, TimeUnit.MILLISECONDS);
            }

            log.info("Simulating {} devices every {} ms", devices.size(), intervalMs);
            if (finite) {
                boolean completedInTime = finished.await(completionTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!completedInTime) {
                    throw new IllegalStateException("Simulator did not finish within " + completionTimeout
                            + ": " + doneMessages.get() + " of " + (long) devices.size() * messagesPerDevice
                            + " messages done, " + failedMessages.get() + " failed");
                }
                if (failedMessages.get() > 0) {
                    throw new IllegalStateException(failedMessages.get() + " of "
                            + (long) devices.size() * messagesPerDevice + " messages failed to publish");
                }
            } else {
                finished.await();
            }
        } finally {
            scheduler.shutdownNow();
            for (Mqtt5AsyncClient c : connected) {
                try {
                    // also stops the automatic (re)connect loop of a client that never got connected
                    c.disconnect().join();
                } catch (RuntimeException e) {
                    log.warn("Disconnect failed: {}", e.getMessage());
                }
            }
        }
    }

    private void publish(ScheduledExecutorService scheduler, Mqtt5AsyncClient client, String deviceKey,
                         String topic, byte[] payload, int attempt, Runnable onDone, AtomicInteger failed) {
        client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(payload).send()
                .whenComplete((result, error) -> {
                    String problem = error != null ? String.valueOf(error.getMessage()) : problemOf(result);
                    if (problem == null) {
                        onDone.run();
                        return;
                    }
                    log.warn("Publish failed for {} (attempt {}): {}", deviceKey, attempt, problem);
                    if (attempt >= MAX_PUBLISH_ATTEMPTS) {
                        failed.incrementAndGet();
                        onDone.run();
                        return;
                    }
                    try {
                        scheduler.schedule(() -> {
                            try {
                                publish(scheduler, client, deviceKey, topic, payload, attempt + 1, onDone, failed);
                            } catch (RuntimeException e) {
                                log.error("Retry failed for {}", deviceKey, e);
                                failed.incrementAndGet();
                                onDone.run();
                            }
                        }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
                    } catch (RuntimeException e) {
                        // scheduler already shut down: the run is over, nothing left to account for
                        log.debug("Retry not scheduled for {}: {}", deviceKey, e.getMessage());
                    }
                });
    }

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
