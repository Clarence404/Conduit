package dev.conduit.common.bus;

import dev.conduit.common.message.RawMessage;
import dev.conduit.common.message.RawMessageCodec;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RedisStreamMessageBus implements MessageBus {

    private final StringRedisTemplate redis;
    private final MessageBusProperties props;

    public RedisStreamMessageBus(StringRedisTemplate redis, MessageBusProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public String publish(RawMessage message) {
        return xadd(props.streamKey(), RawMessageCodec.toFields(message), props.maxLen());
    }

    @Override
    public void ensureGroup(String group) {
        byte[] key = bytes(props.streamKey());
        try {
            redis.execute((RedisCallback<String>) c ->
                    c.streamCommands().xGroupCreate(key, group, ReadOffset.from("0-0"), true));
        } catch (RedisSystemException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            if (!msg.contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    @Override
    public List<Delivery> read(String group, String consumer, int count, Duration block) {
        @SuppressWarnings("unchecked")
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(count).block(block),
                StreamOffset.create(props.streamKey(), ReadOffset.lastConsumed()));
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .map(r -> new Delivery(r.getId().getValue(), toStringMap(r.getValue()), 1))
                .toList();
    }

    @Override
    public List<Delivery> claimStale(String group, String consumer, Duration minIdle, int count) {
        PendingMessages pending = redis.opsForStream()
                .pending(props.streamKey(), group, Range.unbounded(), count);
        List<PendingMessage> stale = pending.stream()
                .filter(p -> p.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0)
                .toList();
        if (stale.isEmpty()) {
            return List.of();
        }
        Map<String, Long> previousCounts = stale.stream()
                .collect(Collectors.toMap(PendingMessage::getIdAsString, PendingMessage::getTotalDeliveryCount));

        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                props.streamKey(), group, consumer, minIdle,
                stale.stream().map(PendingMessage::getId).toArray(RecordId[]::new));

        return claimed.stream()
                .map(r -> {
                    String id = r.getId().getValue();
                    return new Delivery(id, toStringMap(r.getValue()), previousCounts.getOrDefault(id, 0L) + 1);
                })
                .toList();
    }

    @Override
    public void ack(String group, List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        redis.opsForStream().acknowledge(props.streamKey(), group, ids.toArray(String[]::new));
    }

    @Override
    public void deadLetter(String group, Delivery delivery, String reason) {
        Map<String, String> fields = new LinkedHashMap<>(delivery.fields());
        fields.put("reason", reason);
        fields.put("originalId", delivery.id());
        fields.put("deliveryCount", Long.toString(delivery.deliveryCount()));
        fields.put("failedAt", Long.toString(System.currentTimeMillis()));
        xadd(props.deadKey(), fields, props.deadMaxLen());
        ack(group, List.of(delivery.id()));
    }

    private String xadd(String key, Map<String, String> fields, long maxLen) {
        Map<byte[], byte[]> raw = new LinkedHashMap<>();
        fields.forEach((k, v) -> raw.put(bytes(k), bytes(v)));
        RecordId id = redis.execute((RedisCallback<RecordId>) c -> c.streamCommands().xAdd(
                StreamRecords.rawBytes(raw).withStreamKey(bytes(key)),
                XAddOptions.maxlen(maxLen).approximateTrimming(true)));
        if (id == null) {
            throw new IllegalStateException("XADD returned no id for " + key);
        }
        return id.getValue();
    }

    private static Map<String, String> toStringMap(Map<Object, Object> value) {
        Map<String, String> result = new LinkedHashMap<>();
        value.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return result;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
