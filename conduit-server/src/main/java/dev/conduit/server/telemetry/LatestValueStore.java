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

/**
 * 设备最新值的 Redis 存储：每个设备一个 hash（conduit:latest:{deviceKey}），字段为测点标识，值为 JSON {v, ts}，
 * 另有保留字段 __last 记录设备最近一次上报时间。流水线在写 InfluxDB 之后更新，REST 接口读取最新值与在线列表的上报时间。
 */
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

    /**
     * 设备最新值 hash 的 Redis 键。
     */
    public static String key(String deviceKey) {
        return "conduit:latest:" + deviceKey;
    }

    /**
     * 写入一批测点的最新值。由 Lua 脚本原子完成：逐测点比较时间戳，只有不早于当前值的才覆盖（补传的旧数据不会盖掉新值）；__last 仅在 ts 更大时前移。
     * 测点名以 "__" 开头（保留）或值为 null 时抛 IllegalArgumentException，此时不会写入任何内容。
     * @param ts 本批数据的设备时间戳（毫秒）
     */
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

    /**
     * 读取设备全部测点的最新值，按测点标识排序，不含 __last。设备无数据返回空 Map。
     */
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

    /**
     * 批量读取设备最近上报时间（pipeline 一次往返）。返回的 Map 只包含有记录的设备；入参为空直接返回空 Map。
     */
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

    /**
     * 删除设备的全部最新值（设备删除时清理用）。
     */
    public void delete(String deviceKey) {
        redis.delete(key(deviceKey));
    }

    /**
     * 把 Redis 中的 JSON {v, ts} 还原为 LatestValue。
     */
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
