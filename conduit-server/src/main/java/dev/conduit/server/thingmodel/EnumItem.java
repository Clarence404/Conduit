package dev.conduit.server.thingmodel;

/**
 * 枚举属性的一个枚举项（存于 product_property.enum_items 的 jsonb）。
 * @param value 设备上报的整数值
 * @param label 显示名称
 */
public record EnumItem(long value, String label) {
}
