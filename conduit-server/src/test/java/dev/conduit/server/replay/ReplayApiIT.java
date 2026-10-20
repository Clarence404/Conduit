package dev.conduit.server.replay;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.server.auth.Roles;
import dev.conduit.server.pipeline.Backoff;
import dev.conduit.server.pipeline.TelemetryIngestor;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 遥测重放接口集成测试：参数校验（400）、没有运行中任务时取消返回 409、状态查询可用，
 * 以及只有未代入的平台管理员能访问这三个接口。
 */
class ReplayApiIT extends IntegrationTestBase {

    private static final String PATH = "/api/v1/platform/telemetry/replay";

    private String sys;

    @BeforeEach
    void setUp() throws Exception {
        sys = loginAsPlatformAdmin();
    }

    /** 以平台管理员提交一次重放，返回响应以便断言状态码与响应体。 */
    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 某身份访问某方法时的状态码，用来断言权限矩阵。 */
    private int statusOf(String token, HttpMethod method) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, PATH)
                .header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (method == HttpMethod.POST) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{\"from\":0,\"to\":1000}");
        }
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    @Test
    void rejectsAnInvertedOrEmptyRange() throws Exception {
        submit("{\"from\":1760400000000,\"to\":1760400000000}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("from 必须小于 to"));
        submit("{\"from\":1760400001000,\"to\":1760400000000}").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingBoundsAndAnEmptyBody() throws Exception {
        submit("{\"from\":1760400000000}").andExpect(status().isBadRequest());
        submit("{}").andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys))
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBoundsOutsideTheAllowedRange() throws Exception {
        submit("{\"from\":-1,\"to\":1000}").andExpect(status().isBadRequest());
        submit("{\"from\":0,\"to\":253402300800000}").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsASpanLongerThanSixHours() throws Exception {
        submit("{\"from\":0,\"to\":21600001}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("时间跨度不能超过 6 小时"));
    }

    @Test
    void cancellingWithoutARunningTaskIsAConflict() throws Exception {
        mvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("没有正在运行的重放任务"));
    }

    @Test
    void submittingWhileATaskIsRunningIsAConflictAndCancelStopsIt() throws Exception {
        // 执行器只收下任务不执行，所以第一次提交后任务一直是 RUNNING，第二次提交走真实的 409 路径。
        submit("{\"from\":0,\"to\":1000}").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));
        try {
            submit("{\"from\":0,\"to\":1000}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("已有重放任务正在运行"));
            mvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("RUNNING"));
        } finally {
            // 放行被收下的任务：它发现已取消，直接置为 CANCELLED，不影响其他测试看到的「没有运行中任务」。
            HeldExecutor.releaseAll();
        }
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    @Test
    void anImpersonatingPlatformAdminCannotReachAnyOfTheThreeEndpoints() throws Exception {
        String code = tenantCode(createTenant("rp-"));
        String body = mvc.perform(post("/api/v1/platform/tenants/{c}/enter", code)
                        .header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String impersonation = JsonPath.read(body, "$.accessToken");

        assertThat(statusOf(impersonation, HttpMethod.GET)).isEqualTo(403);
        assertThat(statusOf(impersonation, HttpMethod.POST)).isEqualTo(403);
        assertThat(statusOf(impersonation, HttpMethod.DELETE)).isEqualTo(403);
    }

    /** 用只收下任务、等测试放行的执行器替换重放服务，让「任务正在运行」的状态可以确定地构造。 */
    @TestConfiguration
    static class HeldExecutorConfig {

        @Bean
        @Primary
        ReplayService heldReplayService(MessageBus bus, TelemetryIngestor ingestor, Clock clock) {
            return new ReplayService(bus, ingestor, clock,
                    new Backoff(Duration.ofMillis(10), Duration.ofMillis(10), ReplayService.MAX_ATTEMPTS),
                    HeldExecutor::hold);
        }
    }

    /** 收下提交的任务而不执行，直到 {@link #releaseAll()}。 */
    static final class HeldExecutor {
        private static final java.util.List<Runnable> HELD = new java.util.concurrent.CopyOnWriteArrayList<>();

        static void hold(Runnable task) {
            HELD.add(task);
        }

        /** 依次执行并清空已收下的任务。 */
        static void releaseAll() {
            HELD.forEach(Runnable::run);
            HELD.clear();
        }
    }

    @Test
    void theStatusEndpointAnswersThePlatformAdmin() throws Exception {
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").exists());
    }

    @Test
    void tenantRolesCannotReachAnyOfTheThreeEndpoints() throws Exception {
        long tenantId = createTenant("rp-");
        String admin = login(createUser(tenantId, Roles.TENANT_ADMIN, false), TEST_PASSWORD);
        String viewer = login(createUser(tenantId, Roles.TENANT_VIEWER, false), TEST_PASSWORD);

        for (String token : new String[]{admin, viewer}) {
            assertThat(statusOf(token, HttpMethod.GET)).isEqualTo(403);
            assertThat(statusOf(token, HttpMethod.POST)).isEqualTo(403);
            assertThat(statusOf(token, HttpMethod.DELETE)).isEqualTo(403);
        }
    }
}
