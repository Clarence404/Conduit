package dev.conduit.server.thingmodel;

import dev.conduit.server.thingmodel.ThingModelDtos.EnumItemRequest;
import dev.conduit.server.thingmodel.ThingModelDtos.PropertyRequest;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 属性校验（新增、修改、导入共用，V0.2-A spec 5「属性校验」）。 */
final class PropertyRules {

    static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    /** 与 InfluxDB 3 表 property 的时间列、tag 列同名，写入必然被拒。 */
    static final Set<String> RESERVED_IDENTIFIERS = Set.of("time", "tenant_id", "product_key", "device_key");
    static final int MAX_PROPERTIES = 200;
    static final int MAX_ENUM_ITEMS = 64;
    static final int MAX_NAME = 64;
    static final int MAX_UNIT = 16;
    static final int MAX_DESCRIPTION = 256;

    private PropertyRules() {
    }

    /**
     * 解析数据类型名称；null 或不是合法枚举名时返回 null（由 check 报校验错误），不抛异常。
     */
    static DataType parseType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return DataType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 返回全部错误，为空表示合法。 */
    static List<String> check(PropertyRequest r) {
        List<String> errors = new ArrayList<>();
        String id = r.identifier();
        if (id == null || !IDENTIFIER.matcher(id).matches()) {
            errors.add("标识须以字母开头，只含字母、数字、下划线，最长 64 个字符");
        } else if (RESERVED_IDENTIFIERS.contains(id)) {
            errors.add("标识 " + id + " 为保留字");
        }
        if (r.name() == null || r.name().isBlank()) {
            errors.add("名称不能为空");
        } else if (r.name().length() > MAX_NAME) {
            errors.add("名称最长 64 个字符");
        }
        DataType type = parseType(r.dataType());
        if (type == null) {
            errors.add("数据类型必须是 DOUBLE、INT、BOOL、TEXT、ENUM 之一");
        }
        if (r.unit() != null && r.unit().length() > MAX_UNIT) {
            errors.add("单位最长 16 个字符");
        }
        if (r.description() != null && r.description().length() > MAX_DESCRIPTION) {
            errors.add("说明最长 256 个字符");
        }
        if (r.decimals() != null && (r.decimals() < 0 || r.decimals() > 6)) {
            errors.add("小数位数须在 0～6 之间");
        }
        Double min = r.minValue();
        Double max = r.maxValue();
        if ((min != null || max != null) && type != null && type != DataType.DOUBLE && type != DataType.INT) {
            errors.add("只有数值类型可以设置范围");
        }
        if ((min != null && !Double.isFinite(min)) || (max != null && !Double.isFinite(max))) {
            errors.add("范围必须是有限数值");
        } else if (min != null && max != null && min > max) {
            errors.add("最小值不能大于最大值");
        }
        List<EnumItemRequest> items = r.enumItems() == null ? List.of() : r.enumItems();
        if (type == DataType.ENUM) {
            errors.addAll(checkEnumItems(items));
        } else if (type != null && !items.isEmpty()) {
            errors.add("只有枚举类型可以设置枚举项");
        }
        return errors;
    }

    /**
     * 校验枚举项：至少 1 个、至多 64 个，值必须是整数且不重复，名称非空。
     */
    private static List<String> checkEnumItems(List<EnumItemRequest> items) {
        List<String> errors = new ArrayList<>();
        if (items.isEmpty()) {
            errors.add("枚举类型至少需要一个枚举项");
            return errors;
        }
        if (items.size() > MAX_ENUM_ITEMS) {
            errors.add("枚举项最多 64 个");
        }
        Set<Long> seen = new HashSet<>();
        for (EnumItemRequest item : items) {
            if (item == null) {
                errors.add("枚举项不能为空");
                continue;
            }
            Long value = integerValue(item.value());
            if (value == null) {
                errors.add("枚举值必须是整数：" + item.value());
            } else if (!seen.add(value)) {
                errors.add("枚举值重复：" + value);
            }
            if (item.label() == null || item.label().isBlank()) {
                errors.add("枚举项名称不能为空");
            }
        }
        return errors;
    }

    /**
     * 把请求中的数值转为 long；只接受整数类型（Long/Integer/Short/Byte 以及不溢出的 BigInteger），
     * 小数（如 1.5）、溢出值和 null 返回 null。
     */
    static Long integerValue(Number n) {
        return switch (n) {
            case Long l -> l;
            case Integer i -> i.longValue();
            case Short s -> s.longValue();
            case Byte b -> b.longValue();
            case BigInteger b -> b.bitLength() < 64 ? b.longValue() : null;
            case null, default -> null;
        };
    }

    /** 把合法请求写入实体（调用前 check 必须为空）。identifier、dataType 由调用方处理。 */
    static void apply(PropertyRequest r, ProductProperty target) {
        target.setName(r.name().trim());
        target.setUnit(blankToNull(r.unit()));
        target.setMinValue(r.minValue());
        target.setMaxValue(r.maxValue());
        target.setDecimals(r.decimals());
        target.setEnumItems(parseType(r.dataType()) == DataType.ENUM
                ? r.enumItems().stream().map(i -> new EnumItem(integerValue(i.value()), i.label().trim())).toList()
                : null);
        target.setDescription(blankToNull(r.description()));
    }

    /**
     * 空白串视为 null，否则去掉首尾空白。
     */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
