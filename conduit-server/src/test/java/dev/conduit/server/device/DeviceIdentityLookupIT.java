package dev.conduit.server.device;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DeviceIdentityLookupIT extends IntegrationTestBase {

    @Autowired
    DeviceIdentityLookup lookup;

    @Autowired
    DeviceIdentityCache cache;

    @Autowired
    ObjectMapper objectMapper;

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

    @Test
    void legacyEntryWithoutTenantStatusIsServedAsActiveWithoutRepair(CapturedOutput output) throws Exception {
        String dk = unique("dk");
        // V0.2-C 之前写入的缓存条目：没有 tenantStatus 字段
        String legacy = ("{\"deviceKey\":\"%s\",\"tenantId\":7,\"status\":\"ENABLED\",\"secretHash\":\"h\","
                + "\"secretSalt\":\"s\",\"productKey\":\"pk\"}").formatted(dk);
        redis.opsForValue().set(DeviceIdentityCache.key(dk), legacy);
        // 注入的 Spring ObjectMapper 能直接读出旧条目
        assertThat(objectMapper.readValue(legacy, DeviceIdentity.class).tenantStatus()).isNull();

        DeviceIdentity identity = lookup.find(dk).orElseThrow();

        assertThat(identity.tenantStatus()).isNull();
        assertThat(identity.tenantActive()).isTrue();
        assertThat(identity.tenantId()).isEqualTo(7);
        // 没有走损坏条目的修复路径：不记告警、缓存里仍是原样的旧条目
        assertThat(output).doesNotContain("Corrupt identity cache entry");
        assertThat(redis.opsForValue().get(DeviceIdentityCache.key(dk))).isEqualTo(legacy);
    }
}
