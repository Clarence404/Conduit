package dev.conduit.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** DeviceIdentity 单元测试：租户状态判断，以及升级前缓存的 JSON（没有 tenantStatus）按租户启用处理。 */
class DeviceIdentityTest {

    @Test
    void tenantActiveFollowsTheTenantStatus() {
        assertThat(new DeviceIdentity("dk", 1, "ENABLED", "h", "s", "pk", "ACTIVE").tenantActive()).isTrue();
        assertThat(new DeviceIdentity("dk", 1, "ENABLED", "h", "s", "pk", "DISABLED").tenantActive()).isFalse();
        assertThat(new DeviceIdentity("dk", 1, "ENABLED", "h", "s", "pk", null).tenantActive()).isTrue();
    }

    @Test
    void legacyCacheEntriesWithoutTenantStatusCountAsActive() throws Exception {
        String legacy = "{\"deviceKey\":\"dk\",\"tenantId\":1,\"status\":\"ENABLED\",\"secretHash\":\"h\","
                + "\"secretSalt\":\"s\",\"productKey\":\"pk\"}";

        DeviceIdentity identity = new ObjectMapper().readValue(legacy, DeviceIdentity.class);

        assertThat(identity.tenantStatus()).isNull();
        assertThat(identity.tenantActive()).isTrue();
    }
}
