package dev.conduit.server.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 平台用户（表 app_user），属于某个租户。用于 UI 登录；密码只存 BCrypt 哈希。
 * 该表不参与租户行过滤，因为登录时尚无租户上下文，需要全局按用户名查找。
 */
@Data
@TableName("app_user")
public class AppUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String username;
    private String passwordHash;
    private String role;
    private String status;
    private OffsetDateTime createdAt;
}
