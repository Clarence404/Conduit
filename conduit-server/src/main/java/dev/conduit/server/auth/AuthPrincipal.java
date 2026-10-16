package dev.conduit.server.auth;

/**
 * 已认证的调用者身份，由 JWT 解析得到并放入 Spring Security 上下文；tenantId 决定后续数据访问的租户范围。
 *
 * @param userId             用户 id；代入令牌中是平台管理员自己的 id
 * @param tenantId           租户 id；未代入的平台管理员为 null，代入时为目标租户
 * @param username           用户名；代入令牌中是平台管理员的用户名
 * @param role               有效角色（见 {@link Roles}）；代入时为 TENANT_ADMIN
 * @param impersonatorId     代入的平台管理员用户 id；未代入为 null
 * @param mustChangePassword 为 true 时除修改密码等少数接口外一律 403（见 {@link PasswordChangeGateFilter}）
 */
public record AuthPrincipal(long userId, Long tenantId, String username, String role, Long impersonatorId,
                            boolean mustChangePassword) {

    /** 普通身份（未代入、无需改密码）。 */
    public AuthPrincipal(long userId, Long tenantId, String username, String role) {
        this(userId, tenantId, username, role, null, false);
    }

    /** 是否为平台管理员代入租户的身份。 */
    public boolean impersonating() {
        return impersonatorId != null;
    }
}
