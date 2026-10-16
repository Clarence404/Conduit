package dev.conduit.server.tenant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 租户（表 tenant），多租户隔离的最顶层单位：产品、设备、租户用户都归属某个租户。
 * 该表本身不做租户行过滤。编码规则见 {@link TenantCodePolicy}，创建后不可修改；默认租户 default 不能停用或删除。
 */
@Data
@TableName("tenant")
public class Tenant {
    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";
    /** 启动时创建的默认租户编码。 */
    public static final String DEFAULT_CODE = "default";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    /** ACTIVE 或 DISABLED（停用后其用户不能登录、设备不能接入）。 */
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
