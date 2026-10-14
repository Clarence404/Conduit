package dev.conduit.server.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Optional;

@Component
public class DeviceIdentityCache {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdentityCache.class);

    static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public DeviceIdentityCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public static String key(String deviceKey) {
        return "conduit:auth:" + deviceKey;
    }

    public Optional<DeviceIdentity> get(String deviceKey) {
        String json = redis.opsForValue().get(key(deviceKey));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, DeviceIdentity.class));
        } catch (JsonProcessingException e) {
            log.warn("Corrupt identity cache entry for {}, evicting: {}", deviceKey, e.getOriginalMessage());
            evict(deviceKey);
            return Optional.empty();
        }
    }

    public void put(DeviceIdentity identity) {
        try {
            redis.opsForValue().set(key(identity.deviceKey()), objectMapper.writeValueAsString(identity), TTL);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void evict(String deviceKey) {
        redis.delete(key(deviceKey));
    }
}
