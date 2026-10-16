package dev.conduit.server.user;

import dev.conduit.server.auth.AppUser;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/** 用户管理接口的请求与响应对象集合（租户用户管理与平台查看租户用户共用 UserItem）。 */
public final class UserDtos {

    private UserDtos() {
    }

    /**
     * 用户列表项。
     *
     * @param displayName        显示名称；未设置为 null
     * @param role               TENANT_ADMIN 或 TENANT_VIEWER
     * @param status             ACTIVE 或 DISABLED
     * @param mustChangePassword 下次登录是否需要先修改密码
     */
    public record UserItem(String username, String displayName, String role, String status,
                           boolean mustChangePassword, OffsetDateTime createdAt) {

        /** 由实体组装列表项（不含任何密码信息）。 */
        public static UserItem of(AppUser u) {
            return new UserItem(u.getUsername(), u.getDisplayName(), u.getRole(), u.getStatus(),
                    Boolean.TRUE.equals(u.getMustChangePassword()), u.getCreatedAt());
        }
    }

    /** 新建用户；role 只能是 TENANT_ADMIN / TENANT_VIEWER，password 为初始密码（用户首次登录须修改）。 */
    public record CreateUserRequest(@NotBlank String username, String displayName, @NotBlank String role,
                                    @NotBlank String password) {
    }

    /** 修改用户的显示名称（空白即清除）与角色。 */
    public record UpdateUserRequest(String displayName, @NotBlank String role) {
    }

    /** 重置密码：新的初始密码（用户下次登录须修改）。 */
    public record PasswordRequest(@NotBlank String password) {
    }
}
