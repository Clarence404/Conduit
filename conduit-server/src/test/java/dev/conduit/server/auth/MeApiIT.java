package dev.conduit.server.auth;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 个人接口集成测试：修改显示名称；需改密码时只放行 /auth/me、修改密码与登出，其余 403 PASSWORD_CHANGE_REQUIRED；
 * 修改密码成功后换发令牌并解除限制；原密码错误、新密码不合规或与原密码相同 400；代入期间修改密码 409。
 */
class MeApiIT extends IntegrationTestBase {

    private static final String NEW_PASSWORD = "Changed-pass-456";

    @Autowired
    JwtService jwt;

    private ResultActions changePassword(String token, String oldPassword, String newPassword) throws Exception {
        return mvc.perform(put("/api/v1/me/password").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(oldPassword, newPassword)));
    }

    @Test
    void updatesAndClearsTheDisplayName() throws Exception {
        String token = login(createUser(createTenant("me-"), Roles.TENANT_VIEWER, false), TEST_PASSWORD);

        mvc.perform(put("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"  华东运维 张工  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("华东运维 张工"));
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.displayName").value("华东运维 张工"));
        mvc.perform(put("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").isEmpty());
    }

    @Test
    void mustChangePasswordGatesEverythingButTheAllowedEndpoints() throws Exception {
        String username = createUser(createTenant("me-"), Roles.TENANT_ADMIN, true);
        String token = login(username, TEST_PASSWORD);

        mvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        String body = changePassword(token, TEST_PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(false))
                .andReturn().getResponse().getContentAsString();
        String fresh = JsonPath.read(body, "$.accessToken");

        mvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(fresh)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select must_change_password from app_user where username = ?",
                Boolean.class, username)).isFalse();
        login(username, NEW_PASSWORD);
    }

    @Test
    void rejectsWrongWeakAndUnchangedPasswords() throws Exception {
        String token = login(createUser(createTenant("me-"), Roles.TENANT_VIEWER, false), TEST_PASSWORD);

        changePassword(token, "Wrong-pass-1", NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("原密码错误"));
        changePassword(token, TEST_PASSWORD, "short1").andExpect(status().isBadRequest());
        changePassword(token, TEST_PASSWORD, TEST_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("新密码不能与原密码相同"));
    }

    @Test
    void impersonationCannotChangeThePassword() throws Exception {
        long tenant = createTenant("me-");
        long adminId = userId(PLATFORM_ADMIN);
        String token = jwt.issue(new AuthPrincipal(adminId, tenant, PLATFORM_ADMIN, Roles.TENANT_ADMIN, adminId, false),
                JwtService.TokenType.ACCESS);

        changePassword(token, PLATFORM_ADMIN_PASSWORD, NEW_PASSWORD).andExpect(status().isConflict());
    }
}
