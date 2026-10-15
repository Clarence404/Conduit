package dev.conduit.server.auth;

/**
 * 已认证的调用者身份，由 JWT 解析得到并放入 Spring Security 上下文；其中的 tenantId 决定后续数据访问的租户范围。
 *
 * @param userId   用户 id
 * @param tenantId 所属租户 id
 * @param username 用户名
 * @param role     角色（如 ADMIN）
 */
public record AuthPrincipal(long userId, long tenantId, String username, String role) {
}
