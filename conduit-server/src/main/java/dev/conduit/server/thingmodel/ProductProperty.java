package dev.conduit.server.thingmodel;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** 产品属性。可清空的列使用 ALWAYS 更新策略，使 updateById 能把它们写成 null。 */
@Data
@TableName(value = "product_property", autoResultMap = true)
public class ProductProperty {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long productId;
    private String identifier;
    private String name;
    private DataType dataType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String unit;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double minValue;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double maxValue;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer decimals;
    @TableField(typeHandler = EnumItemsTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<EnumItem> enumItems;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
