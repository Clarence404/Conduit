package dev.conduit.server.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 设备身份查询：缓存优先，Redis 不可用时降级直查 PostgreSQL。 */
@Component
public class DeviceIdentityLookup {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdentityLookup.class);

    private final DeviceIdentityCache cache;
    private final DeviceMapper devices;

    public DeviceIdentityLookup(DeviceIdentityCache cache, DeviceMapper devices) {
        this.cache = cache;
        this.devices = devices;
    }

    /**
     * 按 deviceKey 查设备身份（跨租户）：先查缓存，未命中或 Redis 不可用时直查 PostgreSQL，再回填缓存。
     * 回填失败只记录告警，不影响结果。设备不存在返回 empty（不缓存空结果）。
     */
    public Optional<DeviceIdentity> find(String deviceKey) {
        try {
            Optional<DeviceIdentity> cached = cache.get(deviceKey);
            if (cached.isPresent()) {
                return cached;
            }
        } catch (DataAccessException e) {
            log.warn("Identity cache unavailable, falling back to database: {}", e.getMessage());
        }

        DeviceIdentity identity = devices.findIdentity(deviceKey);
        if (identity == null) {
            return Optional.empty();
        }
        try {
            cache.put(identity);
        } catch (DataAccessException e) {
            log.warn("Failed to fill identity cache: {}", e.getMessage());
        }
        return Optional.of(identity);
    }
}
