package dev.conduit.simulator;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 每台设备一个 MQTT 连接，按固定周期以 QoS1 上报；各设备的首次上报在一个周期内均匀错开。 */
public class DeviceSimulator {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulator.class);

    private final String host;
    private final int port;
    private final Duration interval;
    private final int messagesPerDevice;

    public DeviceSimulator(String host, int port, Duration interval, int messagesPerDevice) {
        this.host = host;
        this.port = port;
        this.interval = interval;
        this.messagesPerDevice = messagesPerDevice;
    }

    public void run(List<DeviceCredentials> devices) throws InterruptedException {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
        CountDownLatch finished = new CountDownLatch(messagesPerDevice > 0 ? devices.size() : 1);
        List<Mqtt5AsyncClient> clients = new ArrayList<>();
        long intervalMs = interval.toMillis();

        for (int i = 0; i < devices.size(); i++) {
            DeviceCredentials d = devices.get(i);
            Mqtt5AsyncClient client = MqttClient.builder().useMqttVersion5()
                    .identifier(d.deviceKey())
                    .serverHost(host).serverPort(port)
                    .automaticReconnectWithDefaultConfig()
                    .simpleAuth().username(d.deviceKey()).password(d.secret().getBytes(StandardCharsets.UTF_8))
                    .applySimpleAuth()
                    .buildAsync();
            client.connect().join();
            clients.add(client);

            ReadingGenerator generator = new ReadingGenerator(d.deviceKey().hashCode());
            String topic = "conduit/%s/%s/up/property".formatted(d.productKey(), d.deviceKey());
            AtomicInteger sent = new AtomicInteger();
            AtomicInteger acked = new AtomicInteger();
            long initialDelay = intervalMs * i / Math.max(1, devices.size());

            scheduler.scheduleAtFixedRate(() -> {
                if (messagesPerDevice > 0 && sent.get() >= messagesPerDevice) {
                    return;
                }
                int seq = sent.incrementAndGet();
                byte[] payload = PayloadFactory.property(d.deviceKey() + "-" + seq, System.currentTimeMillis(),
                        generator.next(intervalMs / 1000.0));
                client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(payload).send()
                        .whenComplete((ack, error) -> {
                            if (error != null) {
                                log.warn("Publish failed for {}: {}", d.deviceKey(), error.getMessage());
                            } else if (messagesPerDevice > 0 && acked.incrementAndGet() == messagesPerDevice) {
                                finished.countDown();
                            }
                        });
            }, initialDelay, intervalMs, TimeUnit.MILLISECONDS);
        }

        log.info("Simulating {} devices every {} ms", devices.size(), intervalMs);
        finished.await();
        scheduler.shutdownNow();
        clients.forEach(c -> c.disconnect().join());
    }
}
