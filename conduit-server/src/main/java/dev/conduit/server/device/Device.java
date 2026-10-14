package dev.conduit.server.device;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("device")
public class Device {
    public static final String ENABLED = "ENABLED";
    public static final String DISABLED = "DISABLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long productId;
    private String deviceKey;
    private String name;
    private String secretHash;
    private String secretSalt;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
