package dev.conduit.server.device;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.telemetry.LatestValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeviceApiIT extends IntegrationTestBase {

    @Autowired
    DeviceIdentityCache identityCache;

    @Autowired
    PresenceStore presence;

    @Autowired
    LatestValueStore latest;

    String auth;
    String productKey;

    @BeforeEach
    void setUp() throws Exception {
        auth = bearer(loginAsAdmin());
        String body = mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"meter\"}"))
                .andReturn().getResponse().getContentAsString();
        productKey = JsonPath.read(body, "$.productKey");
    }

    private String create(String name) throws Exception {
        String body = mvc.perform(post("/api/v1/devices").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productKey\":\"" + productKey + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.device.deviceKey").value(matchesPattern("[a-z0-9]{16}")))
                .andExpect(jsonPath("$.device.productKey").value(productKey))
                .andExpect(jsonPath("$.device.status").value("ENABLED"))
                .andExpect(jsonPath("$.device.online").value(false))
                .andExpect(jsonPath("$.deviceSecret").value(matchesPattern("[a-z0-9]{32}")))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.device.deviceKey");
    }

    @Test
    void createStoresOnlyHashedSecret() throws Exception {
        String body = mvc.perform(post("/api/v1/devices").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productKey\":\"" + productKey + "\",\"name\":\"d1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String dk = JsonPath.read(body, "$.device.deviceKey");
        String secret = JsonPath.read(body, "$.deviceSecret");

        Map<String, Object> row = jdbc.queryForMap("select secret_hash, secret_salt from device where device_key = ?", dk);
        assertThat(row.get("secret_hash")).isNotEqualTo(secret);
        assertThat(secretHasher.matches(secret, (String) row.get("secret_salt"), (String) row.get("secret_hash"))).isTrue();
        mvc.perform(get("/api/v1/devices/{dk}", dk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceSecret").doesNotExist());
    }

    @Test
    void createWithUnknownProductIsNotFound() throws Exception {
        mvc.perform(post("/api/v1/devices").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productKey\":\"nope\",\"name\":\"d\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listShowsPresenceAndLastReportAndFilters() throws Exception {
        String online = create("alpha-online");
        create("beta-offline");
        presence.apply(online, true, 1000, "10.0.0.1");
        latest.update(online, 5000, Map.of("Ua", 220.0));

        mvc.perform(get("/api/v1/devices").param("productKey", productKey).param("keyword", "alpha")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].deviceKey").value(online))
                .andExpect(jsonPath("$.items[0].online").value(true))
                .andExpect(jsonPath("$.items[0].lastReportAt").value(5000));

        mvc.perform(get("/api/v1/devices").param("productKey", productKey).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void disablingEvictsIdentityCache() throws Exception {
        String dk = create("d");
        identityCache.put(new DeviceIdentity(dk, 1, "ENABLED", "h", "s", productKey));

        mvc.perform(put("/api/v1/devices/{dk}", dk).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed\",\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"))
                .andExpect(jsonPath("$.status").value("DISABLED"));

        assertThat(identityCache.get(dk)).isEmpty();
    }

    @Test
    void invalidStatusIsBadRequest() throws Exception {
        String dk = create("d");

        mvc.perform(put("/api/v1/devices/{dk}", dk).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"status\":\"BROKEN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetSecretChangesHashAndEvictsCache() throws Exception {
        String dk = create("d");
        String before = jdbc.queryForObject("select secret_hash from device where device_key = ?", String.class, dk);
        identityCache.put(new DeviceIdentity(dk, 1, "ENABLED", "h", "s", productKey));

        mvc.perform(post("/api/v1/devices/{dk}/reset-secret", dk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceKey").value(dk))
                .andExpect(jsonPath("$.deviceSecret").value(matchesPattern("[a-z0-9]{32}")));

        assertThat(jdbc.queryForObject("select secret_hash from device where device_key = ?", String.class, dk))
                .isNotEqualTo(before);
        assertThat(identityCache.get(dk)).isEmpty();
    }

    @Test
    void deleteRemovesDeviceAndRedisState() throws Exception {
        String dk = create("d");
        presence.apply(dk, true, 1000, "ip");
        latest.update(dk, 1000, Map.of("Ua", 1.0));
        identityCache.put(new DeviceIdentity(dk, 1, "ENABLED", "h", "s", productKey));

        mvc.perform(delete("/api/v1/devices/{dk}", dk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/devices/{dk}", dk).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
        assertThat(redis.hasKey(PresenceStore.key(dk))).isFalse();
        assertThat(redis.hasKey(LatestValueStore.key(dk))).isFalse();
        assertThat(redis.hasKey(DeviceIdentityCache.key(dk))).isFalse();
    }

    @Test
    void otherTenantsDeviceIsNotFound() throws Exception {
        TestDevice foreign = createDevice(createTenant("other-"), "ENABLED");

        mvc.perform(get("/api/v1/devices/{dk}", foreign.deviceKey()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
    }
}
