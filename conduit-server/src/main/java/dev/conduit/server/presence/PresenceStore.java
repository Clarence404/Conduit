package dev.conduit.server.presence;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 设备在线状态的 Redis 存储（hash：online / since / clientIp）。写入通过 Lua 脚本保证原子与乱序保护，
 * 由流水线的 {@link PresenceHandler} 写入，REST 查询设备在线状态时读取。
 */
@Component
public class PresenceStore {

    private static final RedisScript<Long> APPLY =
            RedisScript.of(new ClassPathResource("redis/presence-apply.lua"), Long.class);

    private final StringRedisTemplate redis;

    public PresenceStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 设备在线状态的 Redis key。 */
    public static String key(String deviceKey) {
        return "conduit:presence:" + deviceKey;
    }

    /**
     * 应用一次上下线事件。若 Redis 中已有更晚的事件（since 大于 eventTime）则忽略，
     * 防止快速断连重连时旧事件后到覆盖新状态。
     *
     * @return 是否真正写入（false 表示事件过期被丢弃）
     */
    public boolean apply(String deviceKey, boolean online, long eventTime, String clientIp) {
        Long applied = redis.execute(APPLY, List.of(key(deviceKey)),
                Boolean.toString(online), Long.toString(eventTime), clientIp == null ? "" : clientIp);
        return applied != null && applied == 1L;
    }

    /** 查询单个设备的在线状态；从未收到过上下线事件时为 empty。 */
    public Optional<Presence> get(String deviceKey) {
        return Optional.ofNullable(toPresence(redis.opsForHash().entries(key(deviceKey))));
    }

    /** 管道批量查询（用于设备列表）；返回结果只包含有状态记录的设备，没有记录的设备不在 map 中。 */
    public Map<String, Presence> getAll(List<String> deviceKeys) {
        if (deviceKeys.isEmpty()) {
            return Map.of();
        }
        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String dk : deviceKeys) {
                connection.hashCommands().hGetAll(key(dk).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        Map<String, Presence> presence = new LinkedHashMap<>();
        for (int i = 0; i < deviceKeys.size(); i++) {
            @SuppressWarnings("unchecked")
            Presence p = toPresence((Map<Object, Object>) results.get(i));
            if (p != null) {
                presence.put(deviceKeys.get(i), p);
            }
        }
        return presence;
    }

    /** 删除设备的在线状态记录（设备被删除时清理）。 */
    public void delete(String deviceKey) {
        redis.delete(key(deviceKey));
    }

    /** Redis hash 转 Presence；hash 为空或不存在返回 null。 */
    private static Presence toPresence(Map<Object, Object> hash) {
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        return new Presence(
                Boolean.parseBoolean((String) hash.get("online")),
                Long.parseLong((String) hash.get("since")),
                (String) hash.getOrDefault("clientIp", ""));
    }
}
