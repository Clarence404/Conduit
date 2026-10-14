package dev.conduit.server.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class LatestValueStore {

    static final String LAST_FIELD = "__last";
    private static final RedisScript<Long> UPDATE =
            RedisScript.of(new ClassPathResource("redis/latest-update.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public LatestValueStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public static String key(String deviceKey) {
        return "conduit:latest:" + deviceKey;
    }

    public void update(String deviceKey, long ts, Map<String, Object> params) {
        List<String> args = new ArrayList<>(1 + params.size() * 2);
        args.add(Long.toString(ts));
        params.forEach((name, value) -> {
            if (name.startsWith("__")) {
                throw new IllegalArgumentException("Reserved parameter name: " + name);
            }
            if (value == null) {
                throw new IllegalArgumentException("Null value for parameter: " + name);
            }
            args.add(name);
            args.add(toJson(Map.of("v", value, "ts", ts)));
        });
        redis.execute(UPDATE, List.of(key(deviceKey)), args.toArray());
    }

    public Map<String, LatestValue> get(String deviceKey) {
        Map<Object, Object> entries = redis.opsForHash().entries(key(deviceKey));
        Map<String, LatestValue> result = new TreeMap<>();
        entries.forEach((field, json) -> {
            String name = (String) field;
            if (!LAST_FIELD.equals(name)) {
                result.put(name, parse((String) json));
            }
        });
        return result;
    }

    public Map<String, Long> lastReportTimes(List<String> deviceKeys) {
        if (deviceKeys.isEmpty()) {
            return Map.of();
        }
        byte[] lastField = LAST_FIELD.getBytes(StandardCharsets.UTF_8);
        List<Object> values = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String dk : deviceKeys) {
                connection.hashCommands().hGet(key(dk).getBytes(StandardCharsets.UTF_8), lastField);
            }
            return null;
        });
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < deviceKeys.size(); i++) {
            Object value = values.get(i);
            if (value != null) {
                result.put(deviceKeys.get(i), Long.parseLong((String) value));
            }
        }
        return result;
    }

    public void delete(String deviceKey) {
        redis.delete(key(deviceKey));
    }

    private LatestValue parse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new LatestValue(objectMapper.treeToValue(node.get("v"), Object.class), node.get("ts").asLong());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
