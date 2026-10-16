package dev.conduit.server.auth;

import dev.conduit.server.tenant.TenantMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 代入留痕过滤器单元测试：异常逃出 MVC 时记 500 并原样重新抛出，普通写请求记响应状态码。 */
@ExtendWith(OutputCaptureExtension.class)
class ImpersonationLogFilterTest {

    private final ImpersonationLogFilter filter = new ImpersonationLogFilter(mock(TenantMapper.class));

    private static void authenticateAsImpersonator() {
        AuthPrincipal principal = new AuthPrincipal(1L, 5L, "sysadmin", Roles.TENANT_ADMIN, 1L, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void logsStatus500AndRethrowsWhenAnExceptionEscapes(CapturedOutput output) {
        authenticateAsImpersonator();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/products");
        request.setQueryString("secret=query-marker");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class).hasMessage("boom");

        assertThat(output).contains("POST /api/v1/products -> 500").doesNotContain("query-marker");
    }

    @Test
    void logsTheResponseStatusOtherwise(CapturedOutput output) throws Exception {
        authenticateAsImpersonator();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("DELETE", "/api/v1/devices/d1"), response, (req, res) ->
                ((MockHttpServletResponse) res).setStatus(409));

        assertThat(output).contains("DELETE /api/v1/devices/d1 -> 409");
    }
}
