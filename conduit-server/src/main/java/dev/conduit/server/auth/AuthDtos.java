package dev.conduit.server.auth;

import jakarta.validation.constraints.NotBlank;

/** 认证接口（{@link AuthController}）的请求/响应 DTO 集合。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** 登录请求，用户名与密码均不可为空。 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /** 返回给 UI 的当前用户信息（不含任何敏感字段）。 */
    public record UserView(String username, String role) {
    }

    /**
     * 登录/刷新响应。
     *
     * @param accessToken access token
     * @param expiresIn   access token 有效期，单位秒
     * @param user        当前用户
     */
    public record TokenResponse(String accessToken, long expiresIn, UserView user) {
    }
}
