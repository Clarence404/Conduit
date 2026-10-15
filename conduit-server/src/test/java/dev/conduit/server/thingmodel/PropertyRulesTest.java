package dev.conduit.server.thingmodel;

import dev.conduit.server.thingmodel.ThingModelDtos.EnumItemRequest;
import dev.conduit.server.thingmodel.ThingModelDtos.PropertyRequest;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyRulesTest {

    private static PropertyRequest req(String id, String type, Double min, Double max, Integer decimals,
                                       List<EnumItemRequest> items) {
        return new PropertyRequest(id, "名称", type, "V", min, max, decimals, items, null);
    }

    private static List<EnumItemRequest> items(Object... kv) {
        List<EnumItemRequest> list = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            list.add(new EnumItemRequest((Number) kv[i], (String) kv[i + 1]));
        }
        return list;
    }

    private static String errors(PropertyRequest r) {
        return String.join("|", PropertyRules.check(r));
    }

    @Test
    void acceptsValidProperties() {
        assertThat(PropertyRules.check(req("Ua", "DOUBLE", 0.0, 500.0, 1, null))).isEmpty();
        assertThat(PropertyRules.check(req("cnt_1", "INT", null, 10.0, 0, List.of()))).isEmpty();
        assertThat(PropertyRules.check(req("on", "BOOL", null, null, null, null))).isEmpty();
        assertThat(PropertyRules.check(req("mode", "TEXT", null, null, null, null))).isEmpty();
        assertThat(PropertyRules.check(req("st", "ENUM", null, null, null, items(0, "停止", 1L, "运行")))).isEmpty();
        assertThat(PropertyRules.check(req("A" + "b".repeat(63), "DOUBLE", null, null, null, null))).isEmpty();
    }

    @Test
    void rejectsBadIdentifiers() {
        for (String id : new String[]{null, "", "1a", "_a", "__a", "a-b", "a b", "电压", "A" + "b".repeat(64)}) {
            assertThat(errors(req(id, "DOUBLE", null, null, null, null))).as(id).contains("标识");
        }
        for (String id : new String[]{"time", "tenant_id", "product_key", "device_key"}) {
            assertThat(errors(req(id, "DOUBLE", null, null, null, null))).as(id).contains("保留");
        }
    }

    @Test
    void rejectsBadNamesTypesAndLengths() {
        assertThat(errors(new PropertyRequest("a", " ", "DOUBLE", null, null, null, null, null, null))).contains("名称");
        assertThat(errors(new PropertyRequest("a", "x".repeat(65), "DOUBLE", null, null, null, null, null, null)))
                .contains("名称");
        assertThat(errors(req("a", "FLOAT", null, null, null, null))).contains("数据类型");
        assertThat(errors(req("a", null, null, null, null, null))).contains("数据类型");
        assertThat(errors(new PropertyRequest("a", "n", "DOUBLE", "x".repeat(17), null, null, null, null, null)))
                .contains("单位");
        assertThat(errors(new PropertyRequest("a", "n", "DOUBLE", null, null, null, null, null, "x".repeat(257))))
                .contains("说明");
    }

    @Test
    void rejectsBadRangesAndDecimals() {
        assertThat(errors(req("a", "DOUBLE", 5.0, 1.0, null, null))).contains("最小值不能大于最大值");
        assertThat(errors(req("a", "DOUBLE", Double.NEGATIVE_INFINITY, null, null, null))).contains("有限");
        assertThat(errors(req("a", "DOUBLE", null, null, 7, null))).contains("小数位数");
        assertThat(errors(req("a", "DOUBLE", null, null, -1, null))).contains("小数位数");
        for (String type : new String[]{"BOOL", "TEXT", "ENUM"}) {
            assertThat(errors(req("a", type, 0.0, null, null, items(1, "x")))).as(type).contains("范围");
        }
    }

    @Test
    void enforcesEnumItemRules() {
        assertThat(errors(req("a", "ENUM", null, null, null, null))).contains("至少需要一个枚举项");
        assertThat(errors(req("a", "ENUM", null, null, null, List.of()))).contains("至少需要一个枚举项");
        assertThat(errors(req("a", "ENUM", null, null, null, items(1.5, "x")))).contains("整数");
        assertThat(errors(req("a", "ENUM", null, null, null, items(BigInteger.TEN.pow(20), "x")))).contains("整数");
        assertThat(errors(req("a", "ENUM", null, null, null, items(1, "x", 1L, "y")))).contains("重复");
        assertThat(errors(req("a", "ENUM", null, null, null, items(1, " ")))).contains("名称不能为空");
        List<EnumItemRequest> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            many.add(new EnumItemRequest(i, "v" + i));
        }
        assertThat(errors(req("a", "ENUM", null, null, null, many))).contains("最多 64");
        assertThat(errors(req("a", "DOUBLE", null, null, null, items(1, "x")))).contains("只有枚举类型");
    }

    @Test
    void appliesNormalizedValues() {
        ProductProperty target = new ProductProperty();
        PropertyRules.apply(new PropertyRequest("st", " 状态 ", "ENUM", " ", null, null, null,
                items(0, " 停止 ", 1, "运行"), ""), target);

        assertThat(target.getName()).isEqualTo("状态");
        assertThat(target.getUnit()).isNull();
        assertThat(target.getDescription()).isNull();
        assertThat(target.getEnumItems()).containsExactly(new EnumItem(0, "停止"), new EnumItem(1, "运行"));

        PropertyRules.apply(new PropertyRequest("Ua", "U", "DOUBLE", "V", 0.0, 1.0, 1, List.of(), null), target);
        assertThat(target.getEnumItems()).isNull();
        assertThat(target.getMaxValue()).isEqualTo(1.0);
    }
}
