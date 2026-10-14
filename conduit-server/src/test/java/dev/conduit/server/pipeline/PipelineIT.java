package dev.conduit.server.pipeline;

import com.influxdb.client.InfluxDBClient;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.telemetry.LatestValue;
import dev.conduit.server.telemetry.LatestValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.conduit.server.support.InfluxTestSupport.count;
import static org.assertj.core.api.Assertions.assertThat;

class PipelineIT extends IntegrationTestBase {

    @Autowired
    MessageBus bus;

    @Autowired
    PipelineRunner runner;

    @Autowired
    LatestValueStore latest;

    @Autowired
    PresenceStore presence;

    @Autowired
    InfluxDBClient influx;

    TestDevice device;
    long now;

    @BeforeEach
    void setUp() {
        device = createDevice(defaultTenantId(), "ENABLED");
        now = System.currentTimeMillis();
        drainPending();
    }

    /** 清掉之前测试遗留的待处理消息，保证每个测试从干净状态开始。 */
    private void drainPending() {
        while (runner.claimOnce() > 0) {
            // keep draining
        }
        while (runner.pollOnce() > 0) {
            // keep draining
        }
    }

    private String publish(String productKey, String deviceKey, MessageType type, String payload) {
        return bus.publish(new RawMessage(productKey, deviceKey, type,
                payload.getBytes(StandardCharsets.UTF_8), now, Map.of()));
    }

    private Optional<String> deadReason(String originalId) {
        List<MapRecord<String, Object, Object>> dead = redis.opsForStream().range("test:dead", Range.unbounded());
        return dead.stream()
                .filter(r -> originalId.equals(r.getValue().get("originalId")))
                .map(r -> (String) r.getValue().get("reason"))
                .findFirst();
    }

    @Test
    void validPropertyIsWrittenToInfluxAndLatest() {
        publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ua\":220.5,\"Ia\":5}}".formatted(now));

        assertThat(runner.pollOnce()).isEqualTo(1);

        assertThat(count(influx, device.deviceKey(), "Ua")).isEqualTo(1);
        assertThat(latest.get(device.deviceKey()))
                .containsEntry("Ua", new LatestValue(220.5, now))
                .containsEntry("Ia", new LatestValue(5.0, now));
        assertThat(runner.claimOnce()).isZero();
    }

    @Test
    void olderMessageDoesNotOverwriteLatest() {
        publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ua\":2}}".formatted(now));
        publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ua\":1}}".formatted(now - 60_000));

        runner.pollOnce();

        assertThat(latest.get(device.deviceKey())).containsEntry("Ua", new LatestValue(2.0, now));
        assertThat(count(influx, device.deviceKey(), "Ua")).isEqualTo(2);
    }

    @Test
    void unknownDeviceGoesToDeadLetter() {
        String id = publish("pk", unique("ghost"), MessageType.PROPERTY, "{\"params\":{\"Ua\":1}}");

        runner.pollOnce();

        assertThat(deadReason(id)).contains("UNKNOWN_DEVICE");
    }

    @Test
    void disabledDeviceGoesToDeadLetter() {
        TestDevice disabled = createDevice(defaultTenantId(), "DISABLED");
        String id = publish(disabled.productKey(), disabled.deviceKey(), MessageType.PROPERTY, "{\"params\":{\"Ua\":1}}");

        runner.pollOnce();

        assertThat(deadReason(id)).contains("DEVICE_DISABLED");
    }

    @Test
    void productMismatchGoesToDeadLetter() {
        String id = publish("wrong-product", device.deviceKey(), MessageType.PROPERTY, "{\"params\":{\"Ua\":1}}");

        runner.pollOnce();

        assertThat(deadReason(id)).contains("PRODUCT_MISMATCH");
    }

    @Test
    void malformedPayloadGoesToDeadLetter() {
        String id = publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY, "not json");

        runner.pollOnce();

        assertThat(deadReason(id)).contains("MALFORMED_JSON");
    }

    @Test
    void eventTypeIsUnsupportedInV01() {
        String id = publish(device.productKey(), device.deviceKey(), MessageType.EVENT, "{}");

        runner.pollOnce();

        assertThat(deadReason(id)).contains("UNSUPPORTED_TYPE");
    }

    @Test
    void malformedEnvelopeGoesToDeadLetter() {
        String id = redis.opsForStream().add("test:up", Map.of("garbage", "1")).getValue();

        runner.pollOnce();

        assertThat(deadReason(id)).contains("MALFORMED_ENVELOPE");
    }

    @Test
    void presenceEventsUpdatePresence() {
        publish(null, device.deviceKey(), MessageType.PRESENCE_CONNECTED,
                "{\"clientid\":\"%s\",\"ipaddress\":\"10.1.2.3\",\"connected_at\":%d}".formatted(device.deviceKey(), now));

        runner.pollOnce();

        assertThat(presence.get(device.deviceKey())).contains(new Presence(true, now, "10.1.2.3"));
    }

    @Test
    void staleDisconnectDoesNotOverrideNewerConnect() {
        publish(null, device.deviceKey(), MessageType.PRESENCE_CONNECTED,
                "{\"ipaddress\":\"ip\",\"connected_at\":%d}".formatted(now));
        publish(null, device.deviceKey(), MessageType.PRESENCE_DISCONNECTED,
                "{\"disconnected_at\":%d}".formatted(now - 1000));

        runner.pollOnce();

        assertThat(presence.get(device.deviceKey())).map(Presence::online).contains(true);
    }

    @Test
    void unackedMessageIsClaimedAndProcessed() {
        publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ua\":1}}".formatted(now));
        bus.read("conduit-server", "crashed-consumer", 10, Duration.ofMillis(100));

        assertThat(runner.claimOnce()).isEqualTo(1);

        assertThat(count(influx, device.deviceKey(), "Ua")).isEqualTo(1);
    }

    @Test
    void messageExceedingMaxDeliveriesGoesToDeadLetter() {
        String id = publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ua\":1}}".formatted(now));
        bus.read("conduit-server", "crashed-consumer", 10, Duration.ofMillis(100));
        for (int i = 0; i < 5; i++) {
            bus.claimStale("conduit-server", "crashed-consumer", Duration.ZERO, 10);
        }

        runner.claimOnce();

        assertThat(deadReason(id)).contains("MAX_DELIVERY_EXCEEDED");
        assertThat(count(influx, device.deviceKey(), "Ua")).isZero();
    }

    @Test
    void typeConflictOnlyDeadLettersTheConflictingMessage() {
        publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ua\":1}}".formatted(now));
        assertThat(runner.pollOnce()).isEqualTo(1);

        String conflict = publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ua\":\"text\"}}".formatted(now + 1000));
        publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ia\":7}}".formatted(now + 2000));

        assertThat(runner.pollOnce()).isEqualTo(2);

        assertThat(deadReason(conflict)).contains("FIELD_TYPE_CONFLICT");
        assertThat(count(influx, device.deviceKey(), "Ia")).isEqualTo(1);
        assertThat(count(influx, device.deviceKey(), "Ua")).isEqualTo(1);
        assertThat(latest.get(device.deviceKey())).containsKey("Ia");
        assertThat(runner.claimOnce()).isZero();
    }
}
