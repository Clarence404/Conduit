package dev.conduit.server.thingmodel;

import java.time.OffsetDateTime;
import java.util.List;

public final class ThingModelDtos {

    private ThingModelDtos() {
    }

    /** value 用 Number 接收，才能区分 1 与 1.5（Long 会把 1.5 截断为 1）。 */
    public record EnumItemRequest(Number value, String label) {
    }

    /** 新增、修改、导入共用；dataType 用字符串接收，非法值作为校验错误返回而不是解析失败。 */
    public record PropertyRequest(String identifier, String name, String dataType, String unit,
                                  Double minValue, Double maxValue, Integer decimals,
                                  List<EnumItemRequest> enumItems, String description) {

        PropertyRequest withIdentifier(String id) {
            return new PropertyRequest(id, name, dataType, unit, minValue, maxValue, decimals, enumItems, description);
        }

        /** 导出格式：9 个可编辑字段。 */
        static PropertyRequest of(ProductProperty p) {
            List<EnumItemRequest> items = p.getEnumItems() == null ? null
                    : p.getEnumItems().stream().map(i -> new EnumItemRequest(i.value(), i.label())).toList();
            return new PropertyRequest(p.getIdentifier(), p.getName(), p.getDataType().name(), p.getUnit(),
                    p.getMinValue(), p.getMaxValue(), p.getDecimals(), items, p.getDescription());
        }
    }

    public record ThingModelDocument(List<PropertyRequest> properties) {
    }

    public record PropertyView(String identifier, String name, DataType dataType, String unit,
                               Double minValue, Double maxValue, Integer decimals, List<EnumItem> enumItems,
                               String description, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        static PropertyView of(ProductProperty p) {
            return new PropertyView(p.getIdentifier(), p.getName(), p.getDataType(), p.getUnit(), p.getMinValue(),
                    p.getMaxValue(), p.getDecimals(), p.getEnumItems(), p.getDescription(), p.getCreatedAt(),
                    p.getUpdatedAt());
        }
    }
}
