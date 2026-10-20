package dev.conduit.server.auth;

import dev.conduit.server.support.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 认证接口集成测试：登录、刷新、登出、/me，以及当前用户视图中的租户名称。 */
class AuthIT extends IntegrationTestBase {

    private MvcResult login(String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    @Test
    void loginReturnsAccessTokenAndRefreshCookie() throws Exception {
        MvcResult result = login("admin-pass-123");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("conduit_refresh=").contains("HttpOnly")
                .contains("SameSite=Strict").contains("Path=/api/v1/auth");
        assertThat(result.getResponse().getContentAsString()).contains("\"tenantName\":\"默认租户\"");
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(loginAsAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("TENANT_ADMIN"))
                .andExpect(jsonPath("$.tenantName").value("默认租户"));
    }

    @Test
    void wrongPasswordIsUnauthorizedProblem() throws Exception {
        MvcResult result = login("nope");

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
    }

    @Test
    void protectedEndpointRequiresToken() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshIssuesNewAccessTokenFromCookie() throws Exception {
        MvcResult loginResult = login("admin-pass-123");
        Cookie refresh = loginResult.getResponse().getCookie("conduit_refresh");

        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(emptyString())));
    }

    @Test
    void refreshWithoutCookieIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRejectsAccessTokenInCookie() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("conduit_refresh", loginAsAdmin())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutClearsCookie() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent()).andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }
}
