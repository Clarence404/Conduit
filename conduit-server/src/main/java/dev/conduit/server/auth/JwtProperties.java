package dev.conduit.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * JWT 认证配置（前缀 conduit.auth）。
 *
 * @param jwtSecret    HMAC 签名密钥，至少 32 字节，否则 {@link JwtService} 启动失败
 * @param accessTtl    access token 有效期
 * @param refreshTtl   refresh token 有效期，同时是 refresh Cookie 的 Max-Age
 * @param cookieSecure refresh Cookie 是否带 Secure 属性（HTTPS 部署应为 true）
 */
@ConfigurationProperties(prefix = "conduit.auth")
public record JwtProperties(
        String jwtSecret,
        @DefaultValue("15m") Duration accessTtl,
        @DefaultValue("7d") Duration refreshTtl,
        @DefaultValue("false") boolean cookieSecure) {
}
