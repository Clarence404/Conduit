package dev.conduit.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * REST 请求访问日志：DEBUG 级别每个请求一行（方法、路径、状态码、耗时毫秒），方便本地开发时对照前端操作与服务端行为。
 * 只记录路径，不记录查询串、请求头与请求体，因此不会泄露 Authorization、内部 token、设备密钥或登录口令；
 * 健康检查（/actuator）噪声大，直接跳过。优先级最高，以便把安全过滤器返回的 401/403 也记进来。
 * 未开启 DEBUG 时不做任何处理。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    /** DEBUG 未开启或属于 /actuator 的请求不记录。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !log.isDebugEnabled() || request.getRequestURI().startsWith("/actuator");
    }

    /** 放行请求并在结束（含异常）后记录一行：{@code HTTP GET /api/v1/devices -> 200 (12 ms)}。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            log.debug("HTTP {} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - start) / 1_000_000);
        }
    }
}
