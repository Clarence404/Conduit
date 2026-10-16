package dev.conduit.server.platform;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.auth.Roles;
import dev.conduit.server.support.IntegrationTestBase;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 进入租户集成测试：平台管理员进入后以租户管理员身份读写目标租户、看不到其他租户，写请求与进入各记一行 INFO；
 * 被拒绝（403）或冲突（409）的代入写请求同样留痕，且日志不含请求体与查询串；退出后回到平台身份；停用的租户不能进入；代入令牌不能访问平台接口；租户用户不能进入。
 */
@ExtendWith(OutputCaptureExtension.class)
class ImpersonationIT extends IntegrationTestBase {

    private String enter(String code) throws Exception {
        String body = mvc.perform(post("/api/v1/platform/tenants/{c}/enter", code)
                        .header(HttpHeaders.AUTHORIZATION, bearer(loginAsPlatformAdmin())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("conduit_refresh=")))
                .andExpect(jsonPath("$.user.role").value(Roles.TENANT_ADMIN))
                .andExpect(jsonPath("$.user.impersonatedBy").value(PLATFORM_ADMIN))
                .andExpect(jsonPath("$.user.tenantCode").value(code))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.accessToken");
    }

    @Test
    void entersWritesOnlyTheTargetTenantAndLogsWrites(CapturedOutput output) throws Exception {
        long id = createTenant("im-");
        String code = tenantCode(id);
        TestDevice foreign = createDevice(defaultTenantId(), "ENABLED");
        String token = enter(code);

        mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"验收电表（代入）\"}"))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select count(*) from product where tenant_id = ?", Integer.class, id)).isEqualTo(1);
        mvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/products/{pk}", foreign.productKey()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());
        assertThat(output)
                .contains("Platform admin " + PLATFORM_ADMIN + " entered tenant " + code)
                .contains("Impersonated write by platform admin " + PLATFORM_ADMIN + " in tenant " + code
                        + ": POST /api/v1/products -> 201");

        jdbc.update("delete from product where tenant_id = ?", id);
    }

    @Test
    void exitReturnsToThePlatform() throws Exception {
        String token = enter(tenantCode(createTenant("im-")));

        String body = mvc.perform(post("/api/v1/auth/exit-tenant").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value(Roles.PLATFORM_ADMIN))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        mvc.perform(get("/api/v1/platform/overview")
                        .header(HttpHeaders.AUTHORIZATION, bearer(JsonPath.read(body, "$.accessToken"))))
                .andExpect(status().isOk());
    }

    @Test
    void disabledTenantsCannotBeEntered() throws Exception {
        long id = createTenant("im-");
        jdbc.update("update tenant set status = 'DISABLED' where id = ?", id);

        mvc.perform(post("/api/v1/platform/tenants/{c}/enter", tenantCode(id))
                        .header(HttpHeaders.AUTHORIZATION, bearer(loginAsPlatformAdmin())))
                .andExpect(status().isConflict());
    }

    @Test
    void impersonationTokensCannotUseThePlatformApi() throws Exception {
        String token = enter(tenantCode(createTenant("im-")));

        mvc.perform(get("/api/v1/platform/tenants").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantUsersCannotEnter() throws Exception {
        mvc.perform(post("/api/v1/platform/tenants/{c}/enter", tenantCode(createTenant("im-")))
                        .header(HttpHeaders.AUTHORIZATION, bearer(loginAsAdmin())))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectedImpersonatedWritesAreLoggedWithoutBodyOrQuery(CapturedOutput output) throws Exception {
        long id = createTenant("im-");
        String code = tenantCode(id);
        String token = enter(code);
        String existing = createUser(id, Roles.TENANT_VIEWER, false);

        // 409：用户名重复，请求体带一个不应出现在日志里的标记
        mvc.perform(post("/api/v1/users").param("trace", "query-marker-77").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"role\":\"TENANT_VIEWER\",\"password\":\"Body-marker-88\"}"
                                .formatted(existing)))
                .andExpect(status().isConflict());
        // 403：代入令牌不能写平台接口
        mvc.perform(post("/api/v1/platform/tenants/{c}/disable", code).param("trace", "query-marker-78")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden());

        assertThat(output)
                .contains("in tenant " + code + ": POST /api/v1/users -> 409")
                .contains("in tenant " + code + ": POST /api/v1/platform/tenants/" + code + "/disable -> 403")
                .doesNotContain("query-marker-77")
                .doesNotContain("query-marker-78")
                .doesNotContain("Body-marker-88");
    }
}
