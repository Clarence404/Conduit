package dev.conduit.server.pipeline;

import dev.conduit.server.thingmodel.PropertySpec;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按物模型逐测点校验并转换为写入类型（V0.2-A spec 4.2）。
 * 没有物模型时与 V0.1 一致：数值一律转 Double，文本、布尔原样，非有限数值使整条消息 INVALID_PARAMS。
 */
public final class FieldValidator {

    static final int MAX_TEXT_LENGTH = 1024;
    private static final double LONG_MIN = -0x1p63;
    private static final double LONG_MAX_EXCLUSIVE = 0x1p63;

    private FieldValidator() {
    }

    /**
     * 校验并转换一条消息的全部测点。
     *
     * @param model  产品的物模型（测点名到定义）；为空表示产品没有物模型，走 V0.1 规则
     * @param values 解码得到的原始值
     * @throws DecodeException 仅在无物模型时：存在非有限数值或不支持的类型（INVALID_PARAMS）；
     *                         有物模型时问题测点只被剔除，不抛异常
     */
    public static FieldValidation validate(Map<String, PropertySpec> model, Map<String, Object> values) {
        return model.isEmpty() ? withoutModel(values) : withModel(model, values);
    }

    /** V0.1 规则：数值一律转 Double，文本、布尔原样；任一值非法则整条消息失败。 */
    private static FieldValidation withoutModel(Map<String, Object> values) {
        Map<String, Object> accepted = new LinkedHashMap<>();
        values.forEach((name, value) -> accepted.put(name, switch (value) {
            case Number n -> {
                double d = n.doubleValue();
                // 超出 double 范围的字面量会变成 ±Infinity，写入 InfluxDB 会失败
                if (!Double.isFinite(d)) {
                    throw new DecodeException(DeadReason.INVALID_PARAMS, "non-finite number for '" + name + "'");
                }
                yield d;
            }
            case String s -> s;
            case Boolean b -> b;
            case null, default ->
                    throw new DecodeException(DeadReason.INVALID_PARAMS, "unsupported value type for '" + name + "'");
        }));
        return new FieldValidation(accepted, List.of());
    }

    /** 按物模型逐测点校验：未定义或不合规的测点记入 rejected，其余记入 accepted。 */
    private static FieldValidation withModel(Map<String, PropertySpec> model, Map<String, Object> values) {
        Map<String, Object> accepted = new LinkedHashMap<>();
        List<RejectedField> rejected = new ArrayList<>();
        values.forEach((name, value) -> {
            PropertySpec spec = model.get(name);
            if (spec == null) {
                rejected.add(new RejectedField(name, value, RejectReason.UNDEFINED));
                return;
            }
            Outcome outcome = check(spec, value);
            if (outcome.reason() == null) {
                accepted.put(name, outcome.value());
            } else {
                rejected.add(new RejectedField(name, value, outcome.reason()));
            }
        });
        return new FieldValidation(accepted, rejected);
    }

    /** 单个测点的校验结果：reason 为 null 表示通过，value 为转换后的写入值。 */
    private record Outcome(Object value, RejectReason reason) {
        static Outcome accept(Object value) {
            return new Outcome(value, null);
        }

        static Outcome reject(RejectReason reason) {
            return new Outcome(null, reason);
        }
    }

    /**
     * 按测点数据类型检查并转换值。DOUBLE 与 INT 校验最小/最大值（闭区间，未设则不限）；
     * INT 与 ENUM 接受整数值的 Double（如 220.0）；BOOL 须为布尔；
     * TEXT 须为字符串且长度不超过 MAX_TEXT_LENGTH；ENUM 须在枚举标签内。
     */
    private static Outcome check(PropertySpec spec, Object value) {
        return switch (spec.dataType()) {
            case DOUBLE -> {
                Double d = finiteDouble(value);
                if (d == null) {
                    yield Outcome.reject(RejectReason.TYPE_MISMATCH);
                }
                yield inRange(spec, d) ? Outcome.accept(d) : Outcome.reject(RejectReason.OUT_OF_RANGE);
            }
            case INT -> {
                Long l = integer(value);
                if (l == null) {
                    yield Outcome.reject(RejectReason.TYPE_MISMATCH);
                }
                yield inRange(spec, l.doubleValue()) ? Outcome.accept(l) : Outcome.reject(RejectReason.OUT_OF_RANGE);
            }
            case BOOL -> value instanceof Boolean ? Outcome.accept(value) : Outcome.reject(RejectReason.TYPE_MISMATCH);
            case TEXT -> {
                if (!(value instanceof String s)) {
                    yield Outcome.reject(RejectReason.TYPE_MISMATCH);
                }
                yield s.length() <= MAX_TEXT_LENGTH ? Outcome.accept(s) : Outcome.reject(RejectReason.TOO_LONG);
            }
            case ENUM -> {
                Long l = integer(value);
                if (l == null) {
                    yield Outcome.reject(RejectReason.TYPE_MISMATCH);
                }
                yield spec.enumLabels().containsKey(l) ? Outcome.accept(l) : Outcome.reject(RejectReason.ENUM_VALUE);
            }
        };
    }

    /** Long、BigInteger 或 Double 中的有限数值；其他返回 null。 */
    static Double finiteDouble(Object value) {
        if (value instanceof Long || value instanceof BigInteger || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        return null;
    }

    /** 整数判断：Long、long 范围内的 BigInteger、值为整数且在 long 范围内的 Double（如 220.0）。 */
    static Long integer(Object value) {
        return switch (value) {
            case Long l -> l;
            case BigInteger b -> b.bitLength() < 64 ? b.longValue() : null;
            case Double d -> Double.isFinite(d) && d == Math.rint(d) && d >= LONG_MIN && d < LONG_MAX_EXCLUSIVE
                    ? d.longValue() : null;
            case null, default -> null;
        };
    }

    /** 最小/最大值为 null 表示该侧不限；边界值视为在范围内。 */
    private static boolean inRange(PropertySpec spec, double v) {
        return (spec.minValue() == null || v >= spec.minValue()) && (spec.maxValue() == null || v <= spec.maxValue());
    }
}
