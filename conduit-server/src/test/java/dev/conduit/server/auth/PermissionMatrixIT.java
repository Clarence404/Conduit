package dev.conduit.server.auth;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限矩阵集成测试（spec §3.3）：平台管理员、租户管理员、只读用户与代入的平台管理员分别访问平台接口、租户读、租户写、
 * 用户管理与个人接口；/actuator/** 除健康检查外只有平台管理员可访问。「允许」以「不是 401/403」判断（平台与用户管理接口在后续任务实现前返回 404，实现后同样成立）；
 * 租户写用空请求体探测，允许时因参数校验返回 400，不会真的写入数据。
 */
class PermissionMatrixIT extends IntegrationTestBase {

    @Autowired
    JwtService jwt;

    /** 一次探测的结果：各类接口的 HTTP 状态码。 */
    record Probe(int platform, int tenantRead, int tenantWrite, int users, int me) {
    }

    private int statusOf(String token, HttpMethod method, String path) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, path).header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (method == HttpMethod.POST || method == HttpMethod.PUT) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private Probe probe(String token) throws Exception {
        return new Probe(
                statusOf(token, HttpMethod.GET, "/api/v1/platform/overview"),
                statusOf(token, HttpMethod.GET, "/api/v1/products"),
                statusOf(token, HttpMethod.POST, "/api/v1/products"),
                statusOf(token, HttpMethod.GET, "/api/v1/users"),
                statusOf(token, HttpMethod.GET, "/api/v1/auth/me"));
    }

    @Test
    void platformAdminReachesOnlyPlatformAndPersonalEndpoints() throws Exception {
        String token = loginAsPlatformAdmin();
        Probe p = probe(token);

        assertThat(p.platform()).isNotIn(401, 403);
        assertThat(p.tenantRead()).isEqualTo(403);
        assertThat(p.tenantWrite()).isEqualTo(403);
        assertThat(p.users()).isEqualTo(403);
        assertThat(p.me()).isEqualTo(200);
        assertThat(statusOf(token, HttpMethod.GET, "/api/v1/overview")).isEqualTo(403);
        assertThat(statusOf(token, HttpMethod.GET, "/api/v1/codecs")).isEqualTo(403);
    }

    @Test
    void tenantAdminReadsWritesAndManagesUsersButNotThePlatform() throws Exception {
        String admin = createUser(createTenant("pm-"), Roles.TENANT_ADMIN, false);
        Probe p = probe(login(admin, TEST_PASSWORD));

        assertThat(p.platform()).isEqualTo(403);
        assertThat(p.tenantRead()).isEqualTo(200);
        assertThat(p.tenantWrite()).isEqualTo(400);
        assertThat(p.users()).isNotIn(401, 403);
        assertThat(p.me()).isEqualTo(200);
    }

    @Test
    void viewerOnlyReads() throws Exception {
        String viewer = createUser(createTenant("pm-"), Roles.TENANT_VIEWER, false);
        String token = login(viewer, TEST_PASSWORD);
        Probe p = probe(token);

        assertThat(p.platform()).isEqualTo(403);
        assertThat(p.tenantRead()).isEqualTo(200);
        assertThat(p.tenantWrite()).isEqualTo(403);
        assertThat(p.users()).isEqualTo(403);
        assertThat(p.me()).isEqualTo(200);
        assertThat(statusOf(token, HttpMethod.PUT, "/api/v1/devices/dk-none")).isEqualTo(403);
        assertThat(statusOf(token, HttpMethod.DELETE, "/api/v1/products/pk-none")).isEqualTo(403);
        assertThat(statusOf(token, HttpMethod.GET, "/api/v1/codecs")).isEqualTo(200);
    }

    @Test
    void impersonatingPlatformAdminActsAsTenantAdminButLosesThePlatform() throws Exception {
        long tenant = createTenant("pm-");
        long adminId = userId(PLATFORM_ADMIN);
        String token = jwt.issue(new AuthPrincipal(adminId, tenant, PLATFORM_ADMIN, Roles.TENANT_ADMIN, adminId, false),
                JwtService.TokenType.ACCESS);
        Probe p = probe(token);

        assertThat(p.platform()).isEqualTo(403);
        assertThat(p.tenantRead()).isEqualTo(200);
        assertThat(p.tenantWrite()).isEqualTo(400);
        assertThat(p.users()).isNotIn(401, 403);
        assertThat(p.me()).isEqualTo(200);
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.impersonatedBy").value(PLATFORM_ADMIN))
                .andExpect(jsonPath("$.tenantCode").value(tenantCode(tenant)))
                .andExpect(jsonPath("$.role").value(Roles.TENANT_ADMIN));
    }

    @Test
    void anonymousCallsAreUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/platform/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorIsPlatformAdminOnlyExceptHealth() throws Exception {
        long tenant = createTenant("pm-");
        for (String role : new String[]{Roles.TENANT_ADMIN, Roles.TENANT_VIEWER}) {
            String token = login(createUser(tenant, role, false), TEST_PASSWORD);
            assertThat(statusOf(token, HttpMethod.GET, "/actuator/metrics")).as(role).isEqualTo(403);
        }
        assertThat(statusOf(loginAsPlatformAdmin(), HttpMethod.GET, "/actuator/metrics")).isEqualTo(200);
        // 健康检查公开（不带令牌）
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/actuator/metrics")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
