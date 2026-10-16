package dev.conduit.server.tenant;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 两租户隔离验收测试（路线图 V0.2 验收）：经平台接口新建两个租户，各自管理员改密码后建一个产品与一台设备；
 * 双方的产品、设备、最新值、历史与首页统计互不可见。测试结束经接口删除设备、产品与租户。
 */
class TwoTenantIsolationIT extends IntegrationTestBase {

    private static final String INITIAL_PASSWORD = "Init-pass-2026";
    private static final String ACTIVE_PASSWORD = "Active-pass-2026";

    /** 一个准备好的租户：编码、管理员令牌、产品与设备。 */
    record Workspace(String code, String token, String productKey, String deviceKey) {
    }

    private String body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Workspace workspace(String tenantName, String productName, String deviceName) throws Exception {
        String sys = loginAsPlatformAdmin();
        String code = unique("iso-");
        String admin = unique("a");
        mvc.perform(post("/api/v1/platform/tenants").header(HttpHeaders.AUTHORIZATION, bearer(sys))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"code\":\"%s\",\"name\":\"%s\",\"admin\":{\"username\":\"%s\",\"password\":\"%s\"}}")
                                .formatted(code, tenantName, admin, INITIAL_PASSWORD)))
                .andExpect(status().isCreated());
        String first = login(admin, INITIAL_PASSWORD);
        String token = JsonPath.read(body(mvc.perform(put("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(INITIAL_PASSWORD, ACTIVE_PASSWORD)))
                .andExpect(status().isOk())), "$.accessToken");
        String productKey = JsonPath.read(body(mvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\"}".formatted(productName)))
                .andExpect(status().isCreated())), "$.productKey");
        String deviceKey = JsonPath.read(body(mvc.perform(post("/api/v1/devices")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productKey\":\"%s\",\"name\":\"%s\"}".formatted(productKey, deviceName)))
                .andExpect(status().isCreated())), "$.device.deviceKey");
        return new Workspace(code, token, productKey, deviceKey);
    }

    private void assertSeesOnlyItsOwn(Workspace me, Workspace other) throws Exception {
        String auth = bearer(me.token());
        mvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].productKey").value(me.productKey()));
        mvc.perform(get("/api/v1/products/{pk}", other.productKey()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/devices").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].deviceKey").value(me.deviceKey()));
        mvc.perform(get("/api/v1/devices/{dk}", other.deviceKey()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/devices/{dk}/latest", other.deviceKey()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/devices/{dk}/history", other.deviceKey()).param("properties", "Ua")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/overview").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.productCount").value(1))
                .andExpect(jsonPath("$.deviceCount").value(1))
                .andExpect(jsonPath("$.products[0].productKey").value(me.productKey()));
    }

    private void tearDown(Workspace w) throws Exception {
        mvc.perform(delete("/api/v1/devices/{dk}", w.deviceKey()).header(HttpHeaders.AUTHORIZATION, bearer(w.token())))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/products/{pk}", w.productKey()).header(HttpHeaders.AUTHORIZATION, bearer(w.token())))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/platform/tenants/{c}", w.code())
                        .header(HttpHeaders.AUTHORIZATION, bearer(loginAsPlatformAdmin())))
                .andExpect(status().isNoContent());
    }

    @Test
    void twoTenantsSeeOnlyTheirOwnData() throws Exception {
        Workspace east = workspace("验收租户（华东）", "华东电表", "华东1号楼 电表01");
        Workspace south = workspace("验收租户（华南）", "华南水表", "华南1号楼 水表01");

        assertSeesOnlyItsOwn(east, south);
        assertSeesOnlyItsOwn(south, east);

        tearDown(east);
        tearDown(south);
    }
}
