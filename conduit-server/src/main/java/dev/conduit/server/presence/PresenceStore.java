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

@Component
public class PresenceStore {

    private static final RedisScript<Long> APPLY =
            RedisScript.of(new ClassPathResource("redis/presence-apply.lua"), Long.class);

    private final StringRedisTemplate redis;

    public PresenceStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String key(String deviceKey) {
        return "conduit:presence:" + deviceKey;
    }

    public boolean apply(String deviceKey, boolean online, long eventTime, String clientIp) {
        Long applied = redis.execute(APPLY, List.of(key(deviceKey)),
                Boolean.toString(online), Long.toString(eventTime), clientIp == null ? "" : clientIp);
        return applied != null && applied == 1L;
    }

    public Optional<Presence> get(String deviceKey) {
        return Optional.ofNullable(toPresence(redis.opsForHash().entries(key(deviceKey))));
    }

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

    public void delete(String deviceKey) {
        redis.delete(key(deviceKey));
    }

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
