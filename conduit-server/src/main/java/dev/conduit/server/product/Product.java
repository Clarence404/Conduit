package dev.conduit.server.product;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 产品实体（表 product，带 tenant_id）：一类设备的模板，持有物模型（product_property）和解码器选择。设备（Device）归属于某个产品。
 */
@Data
@TableName("product")
public class Product {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String productKey;
    private String name;
    /** 允许通过更新把描述清空，因此 null 也要写入。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    /** 解码器名称，见 DecoderRegistry；新建产品默认 standard-json。 */
    private String codec;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
