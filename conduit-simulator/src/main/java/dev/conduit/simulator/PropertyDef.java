package dev.conduit.simulator;

import java.util.List;

/**
 * 物模型属性定义（模拟器从 GET /api/v1/products/{pk}/properties 读取的字段），用于按属性生成取值。
 *
 * @param dataType  DOUBLE / INT / BOOL / TEXT / ENUM
 * @param minValue  下限，未设置为 null
 * @param maxValue  上限，未设置为 null
 * @param decimals  小数位，未设置为 null
 * @param enumItems 枚举项；非枚举或未设置时为空列表（不为 null）
 */
public record PropertyDef(String identifier, String name, String dataType, String unit, Double minValue,
                          Double maxValue, Integer decimals, List<EnumOption> enumItems) {

    public PropertyDef {
        enumItems = enumItems == null ? List.of() : List.copyOf(enumItems);
    }

    /**
     * 枚举项。
     *
     * @param value 设备上报的整数值
     * @param label 显示名称
     */
    public record EnumOption(long value, String label) {
    }
}
