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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备列表筛选（GET /api/v1/devices 的 online / status / reported / createdFrom / createdTo）集成测试：
 * 单项筛选、组合筛选、创建时间边界、Redis 路径的 total 与分页、租户隔离、参数校验。
 */
class DeviceFilterIT extends IntegrationTestBase {

    @Autowired
    PresenceStore presence;

    @Autowired
    LatestValueStore latest;

    String auth;
    String productKey;

    @BeforeEach
    void setUp() throws Exception {
        auth = bearer(loginAsAdmin());
        productKey = createProduct("filter-product");
    }

    private String createProduct(String name) throws Exception {
        String body = mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.productKey");
    }

    private String create(String product, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/devices").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productKey\":\"" + product + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.device.deviceKey");
    }

    private void disable(String dk) {
        jdbc.update("update device set status = 'DISABLED' where device_key = ?", dk);
    }

    private void setCreatedAt(String dk, long epochMillis) {
        jdbc.update("update device set created_at = ? where device_key = ?",
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC), dk);
    }

    private ResultActions list(String... kv) throws Exception {
        var req = get("/api/v1/devices").header(HttpHeaders.AUTHORIZATION, auth);
        for (int i = 0; i < kv.length; i += 2) {
            req.param(kv[i], kv[i + 1]);
        }
        return mvc.perform(req);
    }

    private static List<String> deviceKeys(String body) {
        return JsonPath.read(body, "$.items[*].deviceKey");
    }

    /** 以本产品限定、单页 100 条列出，返回命中的 deviceKey（按列表顺序）。 */
    private List<String> keys(String... kv) throws Exception {
        List<String> all = new ArrayList<>(List.of(kv));
        all.addAll(List.of("productKey", productKey, "size", "100"));
        String body = list(all.toArray(String[]::new)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return deviceKeys(body);
    }

    private static long hoursAgo(int h) {
        return System.currentTimeMillis() - h * 3_600_000L;
    }

    @Test
    void onlineFilterTrueAndFalse() throws Exception {
        String on = create(productKey, "on");
        String off = create(productKey, "off");
        String never = create(productKey, "never");
        presence.apply(on, true, 1000, "ip");
        presence.apply(off, false, 1000, "");

        assertThat(keys("online", "true")).containsExactly(on);
        assertThat(keys("online", "false")).containsExactlyInAnyOrder(off, never);
    }

    @Test
    void statusFilter() throws Exception {
        String enabled = create(productKey, "e");
        String disabled = create(productKey, "d");
        disable(disabled);

        assertThat(keys("status", "ENABLED")).containsExactly(enabled);
        assertThat(keys("status", "DISABLED")).containsExactly(disabled);
    }

    @Test
    void reportedFilterRecentStaleNever() throws Exception {
        String recent = create(productKey, "recent");
        String stale = create(productKey, "stale");
        String never = create(productKey, "never");
        latest.update(recent, System.currentTimeMillis() - 60_000, Map.of("Ua", 1.0));
        latest.update(stale, hoursAgo(2), Map.of("Ua", 1.0));

        assertThat(keys("reported", "RECENT")).containsExactly(recent);
        assertThat(keys("reported", "STALE")).containsExactly(stale);
        assertThat(keys("reported", "NEVER")).containsExactly(never);
    }

    @Test
    void createdRangeIsInclusiveFromAndExclusiveTo() throws Exception {
        String a = create(productKey, "a");
        String b = create(productKey, "b");
        String c = create(productKey, "c");
        setCreatedAt(a, 1_000_000);
        setCreatedAt(b, 2_000_000);
        setCreatedAt(c, 3_000_000);

        assertThat(keys("createdFrom", "2000000", "createdTo", "3000000")).containsExactly(b);
        assertThat(keys("createdFrom", "2000000")).containsExactlyInAnyOrder(b, c);
        assertThat(keys("createdTo", "2000000")).containsExactly(a);
    }

    @Test
    void combinesOnlineStatusAndProduct() throws Exception {
        String otherProduct = createProduct("other-product");
        String hit = create(productKey, "hit");
        String onlineDisabled = create(productKey, "online-disabled");
        create(productKey, "offline-enabled");
        String otherProductOnline = create(otherProduct, "other");
        disable(onlineDisabled);
        presence.apply(hit, true, 1000, "ip");
        presence.apply(onlineDisabled, true, 1000, "ip");
        presence.apply(otherProductOnline, true, 1000, "ip");

        assertThat(keys("online", "true", "status", "ENABLED")).containsExactly(hit);
    }

    @Test
    void combinesOnlineWithReportedAndKeyword() throws Exception {
        String a = create(productKey, "meter-a");
        String b = create(productKey, "meter-b");
        String c = create(productKey, "pump-c");
        for (String d : List.of(a, b, c)) {
            presence.apply(d, true, 1000, "ip");
        }
        latest.update(a, System.currentTimeMillis(), Map.of("Ua", 1.0));
        latest.update(b, hoursAgo(5), Map.of("Ua", 1.0));
        latest.update(c, System.currentTimeMillis(), Map.of("Ua", 1.0));

        assertThat(keys("online", "true", "reported", "RECENT", "keyword", "meter")).containsExactly(a);
    }

    @Test
    void redisPathTotalAndPagingUseFilteredCount() throws Exception {
        List<String> online = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String d = create(productKey, "d" + i);
            if (i % 2 == 0) {
                presence.apply(d, true, 1000, "ip");
                online.add(d);
            }
        }
        // 默认按 id 倒序：d4, d2, d0
        String p1 = list("productKey", productKey, "online", "true", "page", "1", "size", "2")
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items[0].online").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(deviceKeys(p1)).containsExactly(online.get(2), online.get(1));

        String p2 = list("productKey", productKey, "online", "true", "page", "2", "size", "2")
                .andExpect(jsonPath("$.total").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(deviceKeys(p2)).containsExactly(online.get(0));

        String p3 = list("productKey", productKey, "online", "true", "page", "3", "size", "2")
                .andExpect(jsonPath("$.total").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(deviceKeys(p3)).isEmpty();
    }

    @Test
    void otherTenantsDevicesNeverAppear() throws Exception {
        String mine = create(productKey, "mine");
        TestDevice foreign = createDevice(createTenant("other-"), "ENABLED");
        presence.apply(mine, true, 1000, "ip");
        presence.apply(foreign.deviceKey(), true, 1000, "ip");
        latest.update(foreign.deviceKey(), System.currentTimeMillis(), Map.of("Ua", 1.0));

        String[][] queries = {{"online", "true"}, {"reported", "RECENT"}, {"status", "ENABLED"}, {"createdFrom", "0"}};
        for (String[] q : queries) {
            String body = list(q).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(deviceKeys(body)).doesNotContain(foreign.deviceKey());
        }
        assertThat(keys("online", "true")).containsExactly(mine);
    }

    @Test
    void badParametersAreBadRequest() throws Exception {
        list("online", "maybe").andExpect(status().isBadRequest());
        list("status", "BROKEN").andExpect(status().isBadRequest());
        list("reported", "SOMETIMES").andExpect(status().isBadRequest());
        list("createdFrom", "abc").andExpect(status().isBadRequest());
        list("createdFrom", "2000", "createdTo", "2000").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        list("createdFrom", "3000", "createdTo", "2000").andExpect(status().isBadRequest());
    }

    @Test
    void noFilterKeepsExistingBehaviour() throws Exception {
        String a = create(productKey, "a");
        presence.apply(a, true, 1000, "ip");

        assertThat(keys()).containsExactly(a);
        list("productKey", productKey).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].online").value(true));
    }
}
