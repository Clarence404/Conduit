package dev.conduit.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 强制修改密码的闸门：令牌带 mcp（需要先修改密码）时，除 GET /api/v1/auth/me、PUT /api/v1/me/password、登录、刷新、登出
 * 以及非 /api/ 路径外，一律返回 403，ProblemDetail 属性 code = PASSWORD_CHANGE_REQUIRED。位于 JwtAuthFilter 之后、权限判断之前。
 */
public class PasswordChangeGateFilter extends OncePerRequestFilter {

    /** ProblemDetail 的 code 属性值，前端据此弹出强制改密码对话框。 */
    public static final String CODE = "PASSWORD_CHANGE_REQUIRED";

    private final ObjectMapper objectMapper;

    public PasswordChangeGateFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 已认证且需改密码、请求又不在放行清单内时直接写出 403；其余情况放行。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.mustChangePassword() && !allowed(request)) {
            SecurityConfig.writeProblem(response, objectMapper, HttpStatus.FORBIDDEN, "请先修改密码", CODE);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 需改密码时仍放行的请求。 */
    static boolean allowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return !path.startsWith("/api/")
                || ("GET".equals(method) && "/api/v1/auth/me".equals(path))
                || ("PUT".equals(method) && "/api/v1/me/password".equals(path))
                || "/api/v1/auth/login".equals(path)
                || "/api/v1/auth/refresh".equals(path)
                || "/api/v1/auth/logout".equals(path);
    }
}
