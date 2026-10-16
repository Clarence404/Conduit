package dev.conduit.server.auth;

import java.util.Set;

/**
 * 角色常量（app_user.role 与 JWT 的 role 声明）。平台管理员不属于任何租户；租户管理员可读写本租户并管理用户；只读用户只能查看。
 * 平台管理员进入租户后，令牌中的有效角色是 TENANT_ADMIN。
 */
public final class Roles {

    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String TENANT_VIEWER = "TENANT_VIEWER";

    /** 租户内用户可以使用的角色。 */
    public static final Set<String> TENANT_ROLES = Set.of(TENANT_ADMIN, TENANT_VIEWER);

    private Roles() {
    }

    /** 是否为租户内角色（TENANT_ADMIN / TENANT_VIEWER）；null 返回 false。 */
    public static boolean isTenantRole(String role) {
        return role != null && TENANT_ROLES.contains(role);
    }
}
