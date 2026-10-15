package dev.conduit.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/** Spring Security 配置：无状态 JWT 认证，决定哪些接口免认证以及 401/403 的统一返回格式。 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /**
     * 构建过滤链：登录/刷新/登出、/internal/**（供 EMQX 回调，自行校验）、健康检查免认证，其余一律需要认证；
     * 未认证返回 401、无权限返回 403，均为 problem+json；{@link JwtAuthFilter} 置于用户名密码过滤器之前。
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt, ObjectMapper objectMapper)
            throws Exception {
        http
                // 无会话 + refresh Cookie 为 SameSite=Strict 且只发往 /api/v1/auth，关闭 CSRF
                .csrf(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeProblem(res, objectMapper, HttpStatus.UNAUTHORIZED, "未认证或登录已过期"))
                        .accessDeniedHandler((req, res, ex) ->
                                writeProblem(res, objectMapper, HttpStatus.FORBIDDEN, "无权限")))
                .addFilterBefore(new JwtAuthFilter(jwt), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 直接向响应写入 RFC 7807 problem+json（过滤器层异常不经过 MVC 异常处理器）。 */
    private static void writeProblem(HttpServletResponse response, ObjectMapper objectMapper,
                                     HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        objectMapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
