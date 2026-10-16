package dev.conduit.simulator;

import java.util.Locale;
import java.util.Set;

/**
 * 测点分类（纯函数）：按物模型的类型、标识、名称与单位判断测点适用 spec 5.2 表中的哪条规则。
 * 规则按顺序匹配，先匹配者生效（顺序见实施计划 Task 5）。
 */
public final class PointClassifier {

    static final Set<String> ENERGY_UNITS = Set.of("kwh", "mwh", "wh");
    static final Set<String> VOLUME_UNITS = Set.of("m3");
    static final Set<String> POWER_UNITS = Set.of("w", "kw", "mw");

    private PointClassifier() {
    }

    /** 是否会为该属性生成取值（即分类不是 SKIP）。 */
    public static boolean simulated(PropertyDef p) {
        return classify(p) != PointKind.SKIP;
    }

    /** 判定属性的类别（无产品上下文，按非光伏产品处理）；TEXT、无枚举项的 ENUM 与未知类型为 SKIP。 */
    public static PointKind classify(PropertyDef p) {
        return classify(p, false);
    }

    /**
     * 判定属性的类别，带产品上下文。
     *
     * @param pvModel 产品是否为光伏类（见 {@link #isPvModel}）；为 true 时，原本归为 LOAD 的功率单位测点
     *                （如逆变器的「交流功率」）改为 PV_POWER，跟随日照曲线，夜间为 0
     */
    public static PointKind classify(PropertyDef p, boolean pvModel) {
        String type = p.dataType() == null ? "" : p.dataType().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "ENUM" -> p.enumItems().isEmpty() ? PointKind.SKIP : PointKind.ENUM;
            case "BOOL" -> PointKind.BOOL;
            case "DOUBLE", "INT" -> numeric(p, pvModel);
            default -> PointKind.SKIP;
        };
    }

    /** 数值属性的分类：累计量 → 辐照 → 光伏功率 → 电压 → 负载（电流/功率）→ 温度 → 流量 → 漂移类 → 其他。 */
    private static PointKind numeric(PropertyDef p, boolean pvModel) {
        String unit = normalizeUnit(p.unit());
        String text = text(p);
        boolean daily = isDaily(text);
        if (ENERGY_UNITS.contains(unit) || VOLUME_UNITS.contains(unit)) {
            return daily ? PointKind.DAILY_ENERGY : PointKind.CUMULATIVE;
        }
        if (unit.isEmpty() && (containsAny(text, "累计", "total", "energy") || p.identifier().startsWith("EP"))) {
            return daily ? PointKind.DAILY_ENERGY : PointKind.CUMULATIVE;
        }
        if (unit.equals("w/m2") || containsAny(text, "辐照", "irradiance")) {
            return PointKind.IRRADIANCE;
        }
        if (POWER_UNITS.contains(unit) && isGeneration(text)) {
            return PointKind.PV_POWER;
        }
        if (unit.equals("v") || (unit.isEmpty() && text.contains("电压"))) {
            return PointKind.VOLTAGE;
        }
        if (pvModel && POWER_UNITS.contains(unit)) {
            return PointKind.PV_POWER;
        }
        if (unit.equals("a") || POWER_UNITS.contains(unit)) {
            return PointKind.LOAD;
        }
        if (unit.equals("℃") || containsAny(text, "温度", "temperature")) {
            return PointKind.TEMPERATURE;
        }
        if (unit.equals("m3/h") || containsAny(text, "流量", "flow")) {
            return PointKind.FLOW;
        }
        if (ValueRanges.drift(p) != null) {
            return PointKind.DRIFT;
        }
        return PointKind.OTHER;
    }

    /**
     * 产品是否为光伏类：任一属性的标识或名称含发电关键字（eday / etotal / 发电 / 光伏 / pv / solar），
     * 或是辐照度（W/m² 或辐照关键字）。光伏类产品里的功率测点都按光伏功率生成。
     */
    public static boolean isPvModel(java.util.List<PropertyDef> properties) {
        for (PropertyDef p : properties) {
            String type = p.dataType() == null ? "" : p.dataType().toUpperCase(Locale.ROOT);
            if (!type.equals("DOUBLE") && !type.equals("INT")) {
                continue;
            }
            String text = text(p);
            if (isGeneration(text) || normalizeUnit(p.unit()).equals("w/m2")
                    || containsAny(text, "辐照", "irradiance")) {
                return true;
            }
        }
        return false;
    }

    /** 是否与发电（光伏、逆变器）有关。 */
    static boolean isGeneration(String text) {
        return containsAny(text, "光伏", "发电", "pv", "solar", "eday", "etotal");
    }

    /** 是否为按天归零的累计量。 */
    static boolean isDaily(String text) {
        return containsAny(text, "eday", "daily", "today", "日");
    }

    /** 累计量是否为体积（用水）：单位 m³，或非电能单位且名称与水有关。 */
    static boolean isVolume(String unit, String text) {
        return VOLUME_UNITS.contains(unit) || (!ENERGY_UNITS.contains(unit) && containsAny(text, "水", "water"));
    }

    /** 是否为设备内部温度（随负载升高）。 */
    static boolean isDeviceTemperature(String text) {
        return containsAny(text, "绕组", "机内", "内部", "设备", "模块", "油温", "igbt", "winding", "internal");
    }

    /** 用于关键字匹配的文字：标识 + 空格 + 名称，转小写。 */
    static String text(PropertyDef p) {
        return (p.identifier() + " " + (p.name() == null ? "" : p.name())).toLowerCase(Locale.ROOT);
    }

    /** 规范化单位：小写、去空格，²→2、³→3、°c→℃、μ/µ→u；null 为空串。 */
    static String normalizeUnit(String unit) {
        if (unit == null) {
            return "";
        }
        return unit.trim().toLowerCase(Locale.ROOT)
                .replace("²", "2").replace("³", "3").replace("°c", "℃")
                .replace("μ", "u").replace("µ", "u").replace(" ", "");
    }

    static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
