package dev.conduit.server.thingmodel;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ThingModelCacheIT extends IntegrationTestBase {

    @Autowired
    ThingModelCache cache;

    @Test
    void loadsAnyTenantsProductWithoutTenantContext() {
        TestDevice foreign = createDevice(createTenant("cache-"), "ENABLED");
        setCodec(foreign.productKey(), "flat-json");
        insertProperty(foreign.productKey(), "st", "状态", "ENUM", null, null, null, null,
                "[{\"value\":1,\"label\":\"运行\"}]");

        ProductProfile profile = cache.find(foreign.productKey()).orElseThrow();

        assertThat(profile.codec()).isEqualTo("flat-json");
        assertThat(profile.properties().get("st").dataType()).isEqualTo(DataType.ENUM);
        assertThat(profile.properties().get("st").enumLabels()).containsEntry(1L, "运行");
    }

    @Test
    void unknownProductIsEmpty() {
        assertThat(cache.find(unique("ghost"))).isEmpty();
    }
}
