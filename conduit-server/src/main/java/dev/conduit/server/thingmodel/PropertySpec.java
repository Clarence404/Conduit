package dev.conduit.server.thingmodel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 流水线与最新值接口使用的属性定义快照（不可变）。 */
public record PropertySpec(String identifier, String name, DataType dataType, String unit,
                           Double minValue, Double maxValue, Integer decimals, Map<Long, String> enumLabels) {

    public static PropertySpec of(ProductProperty p) {
        Map<Long, String> labels = new LinkedHashMap<>();
        if (p.getEnumItems() != null) {
            p.getEnumItems().forEach(item -> labels.put(item.value(), item.label()));
        }
        return new PropertySpec(p.getIdentifier(), p.getName(), p.getDataType(), p.getUnit(), p.getMinValue(),
                p.getMaxValue(), p.getDecimals(), Collections.unmodifiableMap(labels));
    }
}
