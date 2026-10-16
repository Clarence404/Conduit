package dev.conduit.server.platform;

import dev.conduit.server.auth.Roles;
import dev.conduit.server.device.DeviceIdentityCache;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台租户管理集成测试：新建租户与首个管理员（事务性，管理员需改密码）、输入规则与重复 409、改名、按状态与关键字筛选、
 * 停用（登录被拒、清除设备身份缓存）与启用、默认租户不能停用/启用/删除、删除（有数据 409、用户随之删除）、
 * 查看租户用户与只重置租户管理员的密码、未知租户 404。
 */
class PlatformTenantApiIT extends IntegrationTestBase {

    private static final String INITIAL_PASSWORD = "Init-pass-2026";

    private String sys;

    @BeforeEach
    void setUp() throws Exception {
        sys = loginAsPlatformAdmin();
    }

    private ResultActions createViaApi(String code, String name, String adminUsername, String password) throws Exception {
        return mvc.perform(post("/api/v1/platform/tenants").header(HttpHeaders.AUTHORIZATION, bearer(sys))
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"code\":\"%s\",\"name\":\"%s\",\"admin\":{\"username\":\"%s\","
                        + "\"displayName\":\"华东管理员\",\"password\":\"%s\"}}")
                        .formatted(code, name, adminUsername, password)));
    }

    private ResultActions call(String path, Object... vars) throws Exception {
        return mvc.perform(post(path, vars).header(HttpHeaders.AUTHORIZATION, bearer(sys)));
    }

    private int tenantRows(String code) {
        return jdbc.queryForObject("select count(*) from tenant where code = ?", Integer.class, code);
    }

    @Test
    void createsATenantWithItsFirstAdmin() throws Exception {
        String code = unique("t-");
        String admin = unique("a");

        createViaApi(code, "验收租户（华东）", admin, INITIAL_PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.name").value("验收租户（华东）"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.adminCount").value(1))
                .andExpect(jsonPath("$.userCount").value(1))
                .andExpect(jsonPath("$.productCount").value(0))
                .andExpect(jsonPath("$.deviceCount").value(0))
                .andExpect(jsonPath("$.onlineCount").value(0));

        mvc.perform(get("/api/v1/platform/tenants").param("keyword", code).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.items[0].code").value(code));
        mvc.perform(get("/api/v1/platform/tenants/{c}/users", code).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(jsonPath("$[0].username").value(admin))
                .andExpect(jsonPath("$[0].displayName").value("华东管理员"))
                .andExpect(jsonPath("$[0].role").value(Roles.TENANT_ADMIN))
                .andExpect(jsonPath("$[0].mustChangePassword").value(true));
        mvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(login(admin, INITIAL_PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void creationIsAtomic() throws Exception {
        String code = unique("t-");

        createViaApi(code, "管理员重名的租户", PLATFORM_ADMIN, INITIAL_PASSWORD).andExpect(status().isConflict());

        assertThat(tenantRows(code)).isZero();
    }

    @Test
    void rejectsInvalidInputAndDuplicateCodes() throws Exception {
        createViaApi("Bad_Code", "非法编码", unique("a"), INITIAL_PASSWORD).andExpect(status().isBadRequest());
        createViaApi(unique("t-"), "弱密码", unique("a"), "weak").andExpect(status().isBadRequest());
        createViaApi(unique("t-"), "非法用户名", "1abc", INITIAL_PASSWORD).andExpect(status().isBadRequest());

        String code = unique("t-");
        createViaApi(code, "验收租户（华东）", unique("a"), INITIAL_PASSWORD).andExpect(status().isCreated());
        createViaApi(code, "重复编码", unique("a"), INITIAL_PASSWORD).andExpect(status().isConflict());
    }

    @Test
    void renamesATenant() throws Exception {
        String code = tenantCode(createTenant("rn-"));

        mvc.perform(put("/api/v1/platform/tenants/{c}", code).header(HttpHeaders.AUTHORIZATION, bearer(sys))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" 验收租户（华南） \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("验收租户（华南）"));
    }

    @Test
    void filtersByStatusAndKeyword() throws Exception {
        long id = createTenant("fs-");
        String code = tenantCode(id);
        jdbc.update("update tenant set status = 'DISABLED' where id = ?", id);

        mvc.perform(get("/api/v1/platform/tenants").param("keyword", code).param("status", "disabled")
                        .header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("DISABLED"));
        mvc.perform(get("/api/v1/platform/tenants").param("keyword", code).param("status", "ACTIVE")
                        .header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/platform/tenants").param("status", "bogus").header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disablingBlocksLoginAndEnablingRestoresIt() throws Exception {
        String code = unique("t-");
        String admin = unique("a");
        createViaApi(code, "验收租户（华东）", admin, INITIAL_PASSWORD).andExpect(status().isCreated());

        call("/api/v1/platform/tenants/{c}/disable", code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        assertThat(loginResult(admin, INITIAL_PASSWORD).getResponse().getStatus()).isEqualTo(401);

        call("/api/v1/platform/tenants/{c}/enable", code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(loginResult(admin, INITIAL_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void disablingEvictsTheDeviceIdentityCache() throws Exception {
        long id = createTenant("dc-");
        TestDevice device = createDevice(id, "ENABLED");
        String key = DeviceIdentityCache.key(device.deviceKey());
        redis.opsForValue().set(key, "{}");

        call("/api/v1/platform/tenants/{c}/disable", tenantCode(id)).andExpect(status().isOk());

        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void theDefaultTenantCannotBeDisabledEnabledOrDeleted() throws Exception {
        call("/api/v1/platform/tenants/{c}/disable", "default").andExpect(status().isConflict());
        call("/api/v1/platform/tenants/{c}/enable", "default").andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/platform/tenants/{c}", "default").header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isConflict());
    }

    @Test
    void deletingNeedsAnEmptyTenantAndRemovesItsUsers() throws Exception {
        long id = createTenant("dl-");
        String code = tenantCode(id);
        String user = createUser(id, Roles.TENANT_ADMIN, false);
        TestDevice device = createDevice(id, "ENABLED");

        mvc.perform(delete("/api/v1/platform/tenants/{c}", code).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isConflict());

        jdbc.update("delete from device where device_key = ?", device.deviceKey());
        jdbc.update("delete from product where product_key = ?", device.productKey());
        mvc.perform(delete("/api/v1/platform/tenants/{c}", code).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isNoContent());

        assertThat(tenantRows(code)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from app_user where username = ?", Integer.class, user)).isZero();
    }

    @Test
    void listsTenantUsersAndResetsOnlyTenantAdmins() throws Exception {
        long id = createTenant("rp-");
        String code = tenantCode(id);
        String admin = createUser(id, Roles.TENANT_ADMIN, false);
        String viewer = createUser(id, Roles.TENANT_VIEWER, false);

        mvc.perform(get("/api/v1/platform/tenants/{c}/users", code).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].username").value(admin))
                .andExpect(jsonPath("$[1].username").value(viewer));

        String reset = "/api/v1/platform/tenants/{c}/users/{u}/reset-password";
        mvc.perform(post(reset, code, viewer).header(HttpHeaders.AUTHORIZATION, bearer(sys))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Reset-pass-9\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(reset, code, admin).header(HttpHeaders.AUTHORIZATION, bearer(sys))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"weak\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(reset, code, "nobody").header(HttpHeaders.AUTHORIZATION, bearer(sys))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Reset-pass-9\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(reset, code, admin).header(HttpHeaders.AUTHORIZATION, bearer(sys))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Reset-pass-9\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select must_change_password from app_user where username = ?",
                Boolean.class, admin)).isTrue();
        login(admin, "Reset-pass-9");
    }

    @Test
    void unknownTenantsAreNotFound() throws Exception {
        mvc.perform(get("/api/v1/platform/tenants/{c}/users", "no-such-tenant").header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isNotFound());
        call("/api/v1/platform/tenants/{c}/disable", "no-such-tenant").andExpect(status().isNotFound());
        call("/api/v1/platform/tenants/{c}/enter", "no-such-tenant").andExpect(status().isNotFound());
    }

    @Test
    void resetsThePasswordOfAnAdminInADisabledTenant() throws Exception {
        long id = createTenant("rd-");
        String admin = createUser(id, Roles.TENANT_ADMIN, false);
        jdbc.update("update tenant set status = 'DISABLED' where id = ?", id);

        mvc.perform(post("/api/v1/platform/tenants/{c}/users/{u}/reset-password", tenantCode(id), admin)
                        .header(HttpHeaders.AUTHORIZATION, bearer(sys))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Reset-pass-9\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select must_change_password from app_user where username = ?",
                Boolean.class, admin)).isTrue();
        assertThat(jdbc.queryForObject("select password_hash from app_user where username = ?", String.class, admin))
                .satisfies(hash -> assertThat(passwordEncoder.matches("Reset-pass-9", hash)).isTrue());
    }
}
