package dev.conduit.server.tenant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 租户（表 tenant），多租户隔离的最顶层单位：产品、设备、用户都归属某个租户。
 * 该表本身不做租户行过滤。
 */
@Data
@TableName("tenant")
public class Tenant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String status;
    private OffsetDateTime createdAt;
}
