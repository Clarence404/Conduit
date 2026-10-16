package dev.conduit.server.device;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceIdentityLookupIT extends IntegrationTestBase {

    @Autowired
    DeviceIdentityLookup lookup;

    @Autowired
    DeviceIdentityCache cache;

    @Test
    void loadsFromDatabaseAndFillsCache() {
        TestDevice device = createDevice(defaultTenantId(), "ENABLED");

        assertThat(cache.get(device.deviceKey())).isEmpty();
        DeviceIdentity identity = lookup.find(device.deviceKey()).orElseThrow();

        assertThat(identity.productKey()).isEqualTo(device.productKey());
        assertThat(identity.tenantId()).isEqualTo(device.tenantId());
        assertThat(cache.get(device.deviceKey())).contains(identity);
        assertThat(redis.getExpire(DeviceIdentityCache.key(device.deviceKey()))).isBetween(1L, 300L);
    }

    @Test
    void servesFromCacheWithoutDatabase() {
        DeviceIdentity cached = new DeviceIdentity(unique("dk"), 99, "ENABLED", "h", "s", "pk", "ACTIVE");
        cache.put(cached);

        assertThat(lookup.find(cached.deviceKey())).contains(cached);
    }

    @Test
    void unknownDeviceIsEmptyAndNotCached() {
        String dk = unique("dk");

        assertThat(lookup.find(dk)).isEmpty();
        assertThat(cache.get(dk)).isEmpty();
    }

    @Test
    void evictRemovesCacheEntry() {
        DeviceIdentity cached = new DeviceIdentity(unique("dk"), 1, "ENABLED", "h", "s", "pk", "ACTIVE");
        cache.put(cached);

        cache.evict(cached.deviceKey());

        assertThat(cache.get(cached.deviceKey())).isEmpty();
    }

    @Test
    void corruptCacheEntryFallsBackToDatabaseAndIsRepaired() {
        TestDevice device = createDevice(defaultTenantId(), "ENABLED");
        redis.opsForValue().set(DeviceIdentityCache.key(device.deviceKey()), "{not json");

        DeviceIdentity identity = lookup.find(device.deviceKey()).orElseThrow();

        assertThat(identity.productKey()).isEqualTo(device.productKey());
        assertThat(cache.get(device.deviceKey())).contains(identity);
    }
}
