package dev.conduit.server.pipeline;

import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.telemetry.InfluxClient;
import dev.conduit.server.telemetry.LatestValue;
import dev.conduit.server.telemetry.LatestValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.conduit.server.support.InfluxTestSupport.awaitCount;
import static dev.conduit.server.support.InfluxTestSupport.count;
import static dev.conduit.server.support.InfluxTestSupport.fieldType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    InfluxClient influx;

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

    private Map<Object, Object> deadRecord(String originalId) {
        return redis.opsForStream().range("test:dead", Range.unbounded()).stream()
                .filter(r -> originalId.equals(r.getValue().get("originalId")))
                .map(MapRecord::getValue)
                .findFirst().orElseThrow();
    }

    @Test
    void validPropertyIsWrittenToInfluxAndLatest() {
        publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ua\":220.5,\"Ia\":5}}".formatted(now));

        assertThat(runner.pollOnce()).isEqualTo(1);

        awaitCount(influx, device.deviceKey(), "Ua", 1);
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
        awaitCount(influx, device.deviceKey(), "Ua", 2);
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

        awaitCount(influx, device.deviceKey(), "Ua", 1);
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
        awaitCount(influx, device.deviceKey(), "Ia", 1);
        awaitCount(influx, device.deviceKey(), "Ua", 1);
        assertThat(latest.get(device.deviceKey())).containsKey("Ia");
        assertThat(runner.claimOnce()).isZero();
    }

    @Test
    void flatJsonProductIsDecodedWithItsCodec() {
        setCodec(device.productKey(), "flat-json");
        publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"id\":\"m1\",\"ts\":%d,\"Ua\":221.5}".formatted(now));

        assertThat(runner.pollOnce()).isEqualTo(1);

        awaitCount(influx, device.deviceKey(), "Ua", 1);
        assertThat(latest.get(device.deviceKey())).containsEntry("Ua", new LatestValue(221.5, now));
        assertThat(runner.claimOnce()).isZero();
    }

    @Test
    void partiallyRejectedMessageStoresValidFieldsAndRecordsTheRest() {
        defineProperty(device.productKey(), "Ua", "DOUBLE", 0.0, 300.0);
        defineProperty(device.productKey(), "Ia", "DOUBLE", null, null);
        String id = publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ua\":999,\"Ia\":5.2,\"Zz\":1}}".formatted(now));

        runner.pollOnce();

        awaitCount(influx, device.deviceKey(), "Ia", 1);
        assertThat(count(influx, device.deviceKey(), "Ua")).isZero();
        Map<Object, Object> dead = deadRecord(id);
        assertThat(dead).containsEntry("reason", "FIELD_REJECTED");
        assertThat((String) dead.get("rejected")).contains(
                "{\"field\":\"Ua\",\"value\":\"999\",\"reason\":\"OUT_OF_RANGE\"}",
                "{\"field\":\"Zz\",\"value\":\"1\",\"reason\":\"UNDEFINED\"}");
        assertThat(latest.get(device.deviceKey())).containsOnlyKeys("Ia");
        assertThat(runner.claimOnce()).as("original message is acked").isZero();
    }

    @Test
    void fullyRejectedMessageIsDeadLettered() {
        defineProperty(device.productKey(), "Ua", "DOUBLE", null, null);
        String id = publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Zz\":1}}".formatted(now));

        runner.pollOnce();

        assertThat(deadReason(id)).contains("FIELD_REJECTED");
        assertThat(deadRecord(id)).containsKey("rejected");
        assertThat(latest.get(device.deviceKey())).isEmpty();
        assertThat(runner.claimOnce()).isZero();
    }

    @Test
    void intPropertyIsStoredAsInfluxInteger() {
        String field = unique("cnt");
        defineProperty(device.productKey(), field, "INT", null, null);
        publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"%s\":42.0}}".formatted(now, field));

        runner.pollOnce();

        awaitCount(influx, device.deviceKey(), field, 1);
        assertThat(fieldType(influx, field)).isEqualTo("Int64");
        assertThat(((Number) latest.get(device.deviceKey()).get(field).value()).longValue()).isEqualTo(42L);
    }

    @Test
    void unknownCodecDeadLettersWholeMessage() {
        setCodec(device.productKey(), "no-such-codec");
        String id = publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"params\":{\"Ua\":1}}");

        runner.pollOnce();

        assertThat(deadReason(id)).contains("UNKNOWN_CODEC");
    }

    @Test
    void thingModelChangesThroughTheApiTakeEffectImmediately() throws Exception {
        String auth = bearer(loginAsAdmin());
        mvc.perform(post("/api/v1/products/{pk}/properties", device.productKey())
                        .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"Ua\",\"name\":\"Ua\",\"dataType\":\"DOUBLE\"}"))
                .andExpect(status().isCreated());
        publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ua\":250}}".formatted(now));
        runner.pollOnce();
        awaitCount(influx, device.deviceKey(), "Ua", 1);

        mvc.perform(put("/api/v1/products/{pk}/properties/Ua", device.productKey())
                        .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ua\",\"dataType\":\"DOUBLE\",\"maxValue\":100}"))
                .andExpect(status().isOk());
        String id = publish(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                "{\"ts\":%d,\"params\":{\"Ua\":250}}".formatted(now + 1000));
        runner.pollOnce();

        assertThat(deadReason(id)).as("cache was evicted, new range applies").contains("FIELD_REJECTED");
    }
}
