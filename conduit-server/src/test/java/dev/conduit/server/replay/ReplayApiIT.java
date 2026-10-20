package dev.conduit.server.replay;

import dev.conduit.server.auth.Roles;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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

    @org.springframework.beans.factory.annotation.Autowired
    ReplayService replayService;

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
    @SuppressWarnings("unchecked")
    void submittingWhileATaskIsRunningIsAConflictAndCancelStopsIt() throws Exception {
        // 构造一个确定处于 RUNNING 的任务并放进服务（真实执行太快，无法稳定地在运行中提交第二次）。
        java.lang.reflect.Field field = ReplayService.class.getDeclaredField("current");
        field.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<ReplayTask> holder =
                (java.util.concurrent.atomic.AtomicReference<ReplayTask>) field.get(replayService);
        ReplayTask previous = holder.get();
        ReplayTask running = new ReplayTask(0, 1000, System.currentTimeMillis());
        holder.set(running);
        try {
            submit("{\"from\":0,\"to\":1000}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("已有重放任务正在运行"));
            mvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("RUNNING"));
            assertThat(running.isCancelled()).isTrue();
        } finally {
            holder.set(previous);
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
