package dev.conduit.server.device;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.telemetry.LatestValueStore;
import org.junit.jupiter.api.AfterEach;
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

    /** 本测试创建的数据，测试结束后清理（设备、产品、租户与对应 Redis 键）。 */
    final List<String> createdDevices = new ArrayList<>();
    final List<String> createdProducts = new ArrayList<>();
    final List<Long> createdTenants = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        auth = bearer(loginAsAdmin());
        productKey = createProduct("筛选测试产品");
    }

    private String createProduct(String name) throws Exception {
        String body = mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(body, "$.productKey");
        createdProducts.add(key);
        return key;
    }

    /** 清理本测试创建的全部数据；设备先于产品、产品先于租户删除。 */
    @AfterEach
    void cleanUp() {
        for (String dk : createdDevices) {
            redis.delete(PresenceStore.key(dk));
            redis.delete(LatestValueStore.key(dk));
            jdbc.update("delete from device where device_key = ?", dk);
        }
        for (Long tenantId : createdTenants) {
            jdbc.update("delete from device where tenant_id = ?", tenantId);
            jdbc.update("delete from product where tenant_id = ?", tenantId);
            jdbc.update("delete from tenant where id = ?", tenantId);
        }
        for (String pk : createdProducts) {
            jdbc.update("delete from product where product_key = ?", pk);
        }
    }

    private String create(String product, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/devices").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productKey\":\"" + product + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(body, "$.device.deviceKey");
        createdDevices.add(key);
        return key;
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
        String on = create(productKey, "1号楼配电室 电表01");
        String off = create(productKey, "1号楼配电室 电表02");
        String never = create(productKey, "2号楼水泵房 电表03");
        presence.apply(on, true, 1000, "ip");
        presence.apply(off, false, 1000, "");

        assertThat(keys("online", "true")).containsExactly(on);
        assertThat(keys("online", "false")).containsExactlyInAnyOrder(off, never);
    }

    @Test
    void statusFilter() throws Exception {
        String enabled = create(productKey, "启用的电表");
        String disabled = create(productKey, "停用的电表");
        disable(disabled);

        assertThat(keys("status", "ENABLED")).containsExactly(enabled);
        assertThat(keys("status", "DISABLED")).containsExactly(disabled);
    }

    @Test
    void reportedFilterRecentStaleNever() throws Exception {
        String recent = create(productKey, "刚上报的电表");
        String stale = create(productKey, "久未上报的电表");
        String never = create(productKey, "2号楼水泵房 电表03");
        latest.update(recent, System.currentTimeMillis() - 60_000, Map.of("Ua", 1.0));
        latest.update(stale, hoursAgo(2), Map.of("Ua", 1.0));

        assertThat(keys("reported", "RECENT")).containsExactly(recent);
        assertThat(keys("reported", "STALE")).containsExactly(stale);
        assertThat(keys("reported", "NEVER")).containsExactly(never);
    }

    @Test
    void createdRangeIsInclusiveFromAndExclusiveTo() throws Exception {
        String a = create(productKey, "电表甲");
        String b = create(productKey, "电表乙");
        String c = create(productKey, "电表丙");
        setCreatedAt(a, 1_000_000);
        setCreatedAt(b, 2_000_000);
        setCreatedAt(c, 3_000_000);

        assertThat(keys("createdFrom", "2000000", "createdTo", "3000000")).containsExactly(b);
        assertThat(keys("createdFrom", "2000000")).containsExactlyInAnyOrder(b, c);
        assertThat(keys("createdTo", "2000000")).containsExactly(a);
    }

    /**
     * 每个干扰设备恰好只违反一个条件（停用 / 离线 / 其他产品），组合查询只剩命中设备；
     * 同时证明每个条件单独使用时确实会放进对应的干扰设备，组合结果不是巧合。
     */
    @Test
    void combinesOnlineStatusAndProduct() throws Exception {
        String otherProduct = createProduct("另一个测试产品");
        String hit = create(productKey, "命中电表");
        String onlineDisabled = create(productKey, "在线但停用电表");
        String offlineEnabled = create(productKey, "离线但启用电表");
        String otherProductOnline = create(otherProduct, "其他产品在线电表");
        disable(onlineDisabled);
        presence.apply(hit, true, 1000, "ip");
        presence.apply(onlineDisabled, true, 1000, "ip");
        presence.apply(otherProductOnline, true, 1000, "ip");

        assertThat(keys("online", "true")).containsExactlyInAnyOrder(hit, onlineDisabled);
        assertThat(keys("status", "ENABLED")).containsExactlyInAnyOrder(hit, offlineEnabled);
        String all = list("online", "true", "status", "ENABLED", "size", "100").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(deviceKeys(all)).contains(hit, otherProductOnline)
                .doesNotContain(onlineDisabled, offlineEnabled);
        assertThat(keys("online", "true", "status", "ENABLED")).containsExactly(hit);
    }

    @Test
    void combinesOnlineWithReportedAndKeyword() throws Exception {
        String a = create(productKey, "电表A");
        String b = create(productKey, "电表B");
        String c = create(productKey, "水泵C");
        for (String d : List.of(a, b, c)) {
            presence.apply(d, true, 1000, "ip");
        }
        latest.update(a, System.currentTimeMillis(), Map.of("Ua", 1.0));
        latest.update(b, hoursAgo(5), Map.of("Ua", 1.0));
        latest.update(c, System.currentTimeMillis(), Map.of("Ua", 1.0));

        assertThat(keys("online", "true", "reported", "RECENT", "keyword", "电表")).containsExactly(a);
    }

    @Test
    void redisPathTotalAndPagingUseFilteredCount() throws Exception {
        List<String> online = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String d = create(productKey, "电表" + i);
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
        String mine = create(productKey, "本租户电表");
        long foreignTenant = createTenant("other-");
        createdTenants.add(foreignTenant);
        TestDevice foreign = createDevice(foreignTenant, "ENABLED");
        createdDevices.add(foreign.deviceKey());
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
    void statusIsCaseInsensitive() throws Exception {
        String enabled = create(productKey, "启用的电表");
        String disabled = create(productKey, "停用的电表");
        disable(disabled);

        assertThat(keys("status", "disabled")).containsExactly(disabled);
        assertThat(keys("status", "Enabled")).containsExactly(enabled);
    }

    @Test
    void createdParametersOutOfRangeAreBadRequest() throws Exception {
        list("createdFrom", "-1").andExpect(status().isBadRequest());
        list("createdTo", "253402300800000").andExpect(status().isBadRequest());
        list("createdFrom", "99999999999999999").andExpect(status().isBadRequest());
        list("createdFrom", "0", "createdTo", "253402300799999").andExpect(status().isOk());
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
        String a = create(productKey, "电表甲");
        presence.apply(a, true, 1000, "ip");

        assertThat(keys()).containsExactly(a);
        list("productKey", productKey).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].online").value(true));
    }
}
