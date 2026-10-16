package dev.conduit.server.user;

import dev.conduit.server.auth.AuthPrincipal;
import dev.conduit.server.auth.JwtService;
import dev.conduit.server.auth.Roles;
import dev.conduit.server.auth.SessionService;
import dev.conduit.server.support.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租户用户管理集成测试：新建（需改密码）、分页与筛选、输入规则与重名 409、编辑显示名称与角色、
 * 「自己」不能删除/禁用/改角色、最后一个启用的租户管理员不能被降级/禁用/删除（代入的平台管理员同样受限）、
 * 两个管理员并发互相降级只有一个成功（租户行锁）、非最后管理员可降级、已禁用的管理员不计入最后管理员规则、
 * 禁用后续期失败、重置密码、删除，以及其他租户的用户不可见（404）。每个测试使用自己新建的租户。
 */
class UserApiIT extends IntegrationTestBase {

    private static final String VIEWER_PASSWORD = "Viewer-pass-1";

    @Autowired
    JwtService jwt;

    private long tenant;
    private String admin;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        tenant = createTenant("ua-");
        admin = createUser(tenant, Roles.TENANT_ADMIN, false);
        token = login(admin, TEST_PASSWORD);
    }

    private ResultActions createViaApi(String bearerToken, String username, String role, String password)
            throws Exception {
        return mvc.perform(post("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer(bearerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"displayName\":\"华东运维 张工\",\"role\":\"%s\",\"password\":\"%s\"}"
                        .formatted(username, role, password)));
    }

    private ResultActions action(String bearerToken, String username, String verb) throws Exception {
        return mvc.perform(post("/api/v1/users/{u}/" + verb, username).header(HttpHeaders.AUTHORIZATION, bearer(bearerToken)));
    }

    private ResultActions update(String bearerToken, String username, String displayName, String role) throws Exception {
        return mvc.perform(put("/api/v1/users/{u}", username).header(HttpHeaders.AUTHORIZATION, bearer(bearerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\",\"role\":\"%s\"}".formatted(displayName, role)));
    }

    private String impersonationToken() {
        long adminId = userId(PLATFORM_ADMIN);
        return jwt.issue(new AuthPrincipal(adminId, tenant, PLATFORM_ADMIN, Roles.TENANT_ADMIN, adminId, false),
                JwtService.TokenType.ACCESS);
    }

    @Test
    void createsListsAndFilters() throws Exception {
        String viewer = unique("v");
        createViaApi(token, viewer, Roles.TENANT_VIEWER, VIEWER_PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(viewer))
                .andExpect(jsonPath("$.displayName").value("华东运维 张工"))
                .andExpect(jsonPath("$.role").value(Roles.TENANT_VIEWER))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.items[0].role").value(Roles.TENANT_ADMIN))
                .andExpect(jsonPath("$.items[1].username").value(viewer));
        mvc.perform(get("/api/v1/users").param("role", "tenant_viewer").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].username").value(viewer));
        mvc.perform(get("/api/v1/users").param("keyword", "张工").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/users").param("status", "DISABLED").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/users").param("role", "PLATFORM_ADMIN").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidInputAndDuplicates() throws Exception {
        createViaApi(token, "1abc", Roles.TENANT_VIEWER, VIEWER_PASSWORD).andExpect(status().isBadRequest());
        createViaApi(token, unique("v"), Roles.TENANT_VIEWER, "short1").andExpect(status().isBadRequest());
        createViaApi(token, unique("v"), Roles.PLATFORM_ADMIN, VIEWER_PASSWORD).andExpect(status().isBadRequest());
        createViaApi(token, admin, Roles.TENANT_VIEWER, VIEWER_PASSWORD).andExpect(status().isConflict());
        createViaApi(token, PLATFORM_ADMIN, Roles.TENANT_VIEWER, VIEWER_PASSWORD).andExpect(status().isConflict());
    }

    @Test
    void newUsersMustChangeTheirPassword() throws Exception {
        String viewer = unique("v");
        createViaApi(token, viewer, Roles.TENANT_VIEWER, VIEWER_PASSWORD).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(login(viewer, VIEWER_PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void updatesTheDisplayNameAndTheRole() throws Exception {
        String viewer = createUser(tenant, Roles.TENANT_VIEWER, false);

        update(token, viewer, "华东运维 李工", Roles.TENANT_ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("华东运维 李工"))
                .andExpect(jsonPath("$.role").value(Roles.TENANT_ADMIN));
    }

    @Test
    void protectsTheActingAdmin() throws Exception {
        createUser(tenant, Roles.TENANT_ADMIN, false);

        mvc.perform(delete("/api/v1/users/{u}", admin).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict());
        action(token, admin, "disable").andExpect(status().isConflict());
        update(token, admin, "", Roles.TENANT_VIEWER).andExpect(status().isConflict());
        update(token, admin, "华东管理员", Roles.TENANT_ADMIN).andExpect(status().isOk());
    }

    @Test
    void keepsTheLastActiveTenantAdmin() throws Exception {
        String platform = impersonationToken();

        action(platform, admin, "disable").andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/users/{u}", admin).header(HttpHeaders.AUTHORIZATION, bearer(platform)))
                .andExpect(status().isConflict());
        update(platform, admin, "", Roles.TENANT_VIEWER).andExpect(status().isConflict());

        createViaApi(platform, unique("a"), Roles.TENANT_ADMIN, VIEWER_PASSWORD).andExpect(status().isCreated());
        action(platform, admin, "disable").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        action(platform, admin, "enable").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void disablingAUserStopsItsNextRefresh() throws Exception {
        String viewer = createUser(tenant, Roles.TENANT_VIEWER, false);
        Cookie refresh = loginResult(viewer, TEST_PASSWORD).getResponse().getCookie(SessionService.REFRESH_COOKIE);

        action(token, viewer, "disable").andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void resetsThePasswordAndRequiresAChange() throws Exception {
        String viewer = createUser(tenant, Roles.TENANT_VIEWER, false);

        mvc.perform(post("/api/v1/users/{u}/reset-password", viewer).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"weak\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/users/{u}/reset-password", viewer).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Reset-pass-9\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select must_change_password from app_user where username = ?",
                Boolean.class, viewer)).isTrue();
        login(viewer, "Reset-pass-9");
    }

    @Test
    void deletesUsers() throws Exception {
        String viewer = createUser(tenant, Roles.TENANT_VIEWER, false);

        mvc.perform(delete("/api/v1/users/{u}", viewer).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/users/{u}", viewer).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from app_user where username = ?", Integer.class, viewer))
                .isZero();
    }

    @Test
    void usersOfOtherTenantsAreInvisible() throws Exception {
        String foreign = createUser(createTenant("ub-"), Roles.TENANT_VIEWER, false);

        update(token, foreign, "", Roles.TENANT_ADMIN).andExpect(status().isNotFound());
        action(token, foreign, "disable").andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/users/{u}", foreign).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void twoAdminsDemotingEachOtherConcurrentlyLeaveExactlyOne() throws Exception {
        String other = createUser(tenant, Roles.TENANT_ADMIN, false);
        String otherToken = login(other, TEST_PASSWORD);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            // 两人用各自的令牌（角色在令牌里，互相降级时对方的令牌仍是管理员）同时降级对方
            for (String[] pair : new String[][]{{token, other}, {otherToken, admin}}) {
                Callable<Integer> call = () -> {
                    ready.countDown();
                    go.await();
                    return update(pair[0], pair[1], "", Roles.TENANT_VIEWER).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from app_user where tenant_id = ? and role = ? and status = 'ACTIVE'",
                Integer.class, tenant, Roles.TENANT_ADMIN)).isEqualTo(1);
    }

    @Test
    void demotingANonLastAdminWorks() throws Exception {
        String other = createUser(tenant, Roles.TENANT_ADMIN, false);

        update(token, other, "", Roles.TENANT_VIEWER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(Roles.TENANT_VIEWER));
    }

    @Test
    void aDisabledAdminDoesNotCountTowardTheLastAdminRule() throws Exception {
        String disabled = createUser(tenant, Roles.TENANT_ADMIN, false);
        jdbc.update("update app_user set status = 'DISABLED' where username = ?", disabled);
        String platform = impersonationToken();

        // 本租户只有 admin 一个启用的管理员，已禁用的不算：不能降级、禁用、删除
        update(platform, admin, "", Roles.TENANT_VIEWER).andExpect(status().isConflict());
        action(platform, admin, "disable").andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/users/{u}", admin).header(HttpHeaders.AUTHORIZATION, bearer(platform)))
                .andExpect(status().isConflict());
        // 已禁用的管理员本身可以随意降级、删除
        update(platform, disabled, "", Roles.TENANT_VIEWER).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/users/{u}", disabled).header(HttpHeaders.AUTHORIZATION, bearer(platform)))
                .andExpect(status().isNoContent());
    }
}
