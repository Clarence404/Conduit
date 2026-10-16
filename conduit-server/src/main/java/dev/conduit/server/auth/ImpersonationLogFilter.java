package dev.conduit.server.auth;

import dev.conduit.server.tenant.Tenant;
import dev.conduit.server.tenant.TenantMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 代入操作留痕：平台管理员代入租户期间的每个写请求（POST/PUT/DELETE/PATCH，/api/v1/auth/** 除外）在完成后记一行 INFO：
 * 平台管理员用户名、租户编码、方法、路径与最终状态码。不记请求体与查询串。V1.0 并入审计表。
 */
public class ImpersonationLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationLogFilter.class);
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final TenantMapper tenants;

    public ImpersonationLogFilter(TenantMapper tenants) {
        this.tenants = tenants;
    }

    /** 代入的写请求放行后记日志（finally 中，含被拒绝或出错的请求；异常逃出时记 500 并重新抛出）；其余请求直接放行。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthPrincipal principal = currentPrincipal();
        if (principal == null || !principal.impersonating() || !WRITE_METHODS.contains(request.getMethod())
                || request.getRequestURI().startsWith("/api/v1/auth/")) {
            chain.doFilter(request, response);
            return;
        }
        // 异常逃出 MVC 时响应状态还没写出（容器随后才返回 500），日志按 500 记录并原样重新抛出
        int status = 0;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            throw e;
        } finally {
            log.info("Impersonated write by platform admin {} in tenant {}: {} {} -> {}", principal.username(),
                    tenantCode(principal.tenantId()), request.getMethod(), request.getRequestURI(),
                    status != 0 ? status : response.getStatus());
        }
    }

    /** 当前请求的认证身份；未认证为 null。 */
    private static AuthPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthPrincipal p ? p : null;
    }

    /** 租户编码；查询失败或租户不存在时退回租户 id，日志本身不能让请求失败。 */
    private String tenantCode(Long tenantId) {
        try {
            Tenant tenant = tenants.selectById(tenantId);
            return tenant == null ? String.valueOf(tenantId) : tenant.getCode();
        } catch (RuntimeException e) {
            return String.valueOf(tenantId);
        }
    }
}
