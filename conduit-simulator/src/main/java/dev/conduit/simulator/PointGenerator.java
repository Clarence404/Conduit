package dev.conduit.simulator;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

/**
 * 单个测点的取值生成器（每台设备每个测点一个实例，保存游走、云层与累计状态），实现 spec 5.2 的各条规则。
 * 随机数以 deviceKey 与 identifier 为种子，同一设备每次启动得到相同的序列；状态只在内存中，重启后从合理初值开始。
 */
final class PointGenerator {

    /** 一轮读数中已生成的功率与流量合计，供累计量按功率/流量积分。 */
    static final class Rates {
        double pvKw;
        double loadKw;
        double flowM3h;
        boolean hasPv;
        boolean hasLoad;
        boolean hasFlow;
    }

    /**
     * 设备的额定量，供没有对应功率/流量测点的累计量估算速率与初值。
     *
     * @param pvKw    光伏容量（kW）
     * @param loadKw  用电负荷额定值（kW）
     * @param flowM3h 用水流量额定值（m³/h）
     */
    record Nominals(double pvKw, double loadKw, double flowM3h) {
    }

    private static final long HOUR_MS = 3_600_000L;

    final PropertyDef def;
    final PointKind kind;
    private final String unit;
    private final String text;
    private final long seed;
    private final Random random;
    private final double nominal;
    private final double phaseOffset;
    private final double[] range;
    private boolean started;
    private double state;
    private double cloud = 0.9;
    private LocalDate day;

    PointGenerator(PropertyDef def, String deviceKey) {
        this.def = def;
        this.kind = PointClassifier.classify(def);
        this.unit = PointClassifier.normalizeUnit(def.unit());
        this.text = PointClassifier.text(def);
        this.seed = deviceKey.hashCode() * 31L + def.identifier().hashCode();
        this.random = new Random(seed);
        this.nominal = nominal();
        this.phaseOffset = phaseOffset(def.identifier());
        this.range = switch (kind) {
            case DRIFT -> ValueRanges.drift(def);
            case OTHER -> ValueRanges.infer(def);
            default -> null;
        };
    }

    /** 是否为累计量（需在本轮其他测点之后计算）。 */
    boolean cumulative() {
        return kind == PointKind.CUMULATIVE || kind == PointKind.DAILY_ENERGY;
    }

    /** 额定值换算为 kW（电流测点为 0，不计入用电功率）。 */
    double nominalKw() {
        return unit.equals("a") ? 0 : toKw(nominal);
    }

    double nominalValue() {
        return nominal;
    }

    /**
     * 生成非累计测点的值，并把光伏功率、用电功率（非电流）、流量计入 rates。
     *
     * @param hour 本地时间的小数小时
     */
    Object next(long epochMillis, double hour, Rates rates) {
        return switch (kind) {
            case ENUM -> enumValue(epochMillis);
            case BOOL -> boolValue(epochMillis);
            default -> {
                double raw = instantaneous(hour);
                addToRates(raw, rates);
                yield ValueShaper.shape(def, raw);
            }
        };
    }

    /**
     * 生成累计测点的值：按本轮功率/流量（没有对应测点时按额定值 × 日曲线）积分 intervalSeconds；
     * 日累计在本地日期变化时归零；有上限时不超过上限。除日累计归零外只增不减。
     */
    Object accumulate(double hour, LocalDate date, double intervalSeconds, Rates rates, Nominals nominals) {
        if (!started) {
            state = initialTotal(hour, nominals);
            day = date;
            started = true;
        }
        if (kind == PointKind.DAILY_ENERGY && !date.equals(day)) {
            day = date;
            state = 0;
        }
        state += rate(hour, rates, nominals) * intervalSeconds / 3600 * unitScale();
        if (def.maxValue() != null) {
            state = Math.min(state, def.maxValue());
        }
        return ValueShaper.shape(def, state);
    }

    /** 非累计数值测点的原始值。 */
    private double instantaneous(double hour) {
        return switch (kind) {
            case VOLTAGE -> 220 + phaseOffset + random.nextGaussian() * 0.6;
            case LOAD -> nominal * DayCurves.loadFactor(hour) * (1 + random.nextGaussian() * 0.03);
            case PV_POWER, IRRADIANCE -> solar(hour);
            case TEMPERATURE -> temperature(hour);
            case FLOW -> Math.max(0, nominal * DayCurves.flowFactor(hour) * (1 + random.nextGaussian() * 0.05));
            default -> walk();
        };
    }

    /** 光伏功率 / 辐照度：容量 × 日照 × 云层系数（0.55～1 之间随机游走），夜间恰为 0。 */
    private double solar(double hour) {
        cloud = DayCurves.clamp(cloud + random.nextGaussian() * 0.05, 0.55, 1.0);
        double sun = DayCurves.sun(hour);
        return sun == 0 ? 0 : nominal * sun * cloud;
    }

    /** 环境温度按昼夜变化；设备内部温度在环境温度上加 10 + 30 × 负荷系数。 */
    private double temperature(double hour) {
        double ambient = DayCurves.ambientTemperature(hour) + random.nextGaussian() * 0.3;
        return PointClassifier.isDeviceTemperature(text) ? ambient + 10 + 30 * DayCurves.loadFactor(hour) : ambient;
    }

    /** 范围内均值回归的随机游走：首次取范围中部附近，之后每步向中点回拉 2% 并叠加范围 2% 的噪声。 */
    private double walk() {
        double lo = range[0];
        double hi = range[1];
        double span = hi - lo;
        if (!started) {
            state = lo + span * (0.3 + 0.4 * random.nextDouble());
            started = true;
        } else {
            state += ((lo + hi) / 2 - state) * 0.02 + random.nextGaussian() * span * 0.02;
        }
        state = DayCurves.clamp(state, lo, hi);
        return state;
    }

    /** 把本测点的原始值计入本轮速率合计（光伏功率、功率单位的负载、流量）。 */
    private void addToRates(double raw, Rates rates) {
        switch (kind) {
            case PV_POWER -> {
                rates.pvKw += toKw(raw);
                rates.hasPv = true;
            }
            case LOAD -> {
                if (!unit.equals("a")) {
                    rates.loadKw += toKw(raw);
                    rates.hasLoad = true;
                }
            }
            case FLOW -> {
                rates.flowM3h += raw;
                rates.hasFlow = true;
            }
            default -> {
            }
        }
    }

    /** 累计量的速率（kW 或 m³/h）：用水 → 流量，发电 → 光伏功率，其余 → 用电功率。 */
    private double rate(double hour, Rates rates, Nominals n) {
        if (PointClassifier.isVolume(unit, text)) {
            return rates.hasFlow ? rates.flowM3h : n.flowM3h() * DayCurves.flowFactor(hour);
        }
        if (PointClassifier.isGeneration(text)) {
            return rates.hasPv ? rates.pvKw : n.pvKw() * DayCurves.sun(hour) * 0.85;
        }
        return rates.hasLoad ? rates.loadKw : n.loadKw() * DayCurves.loadFactor(hour);
    }

    /** 累计量初值：日累计为 0 点至今的曲线积分，终身累计取 1000～10000 kWh 或 500～5000 m³；有上限时不超过一半。 */
    private double initialTotal(double hour, Nominals n) {
        double value;
        boolean volume = PointClassifier.isVolume(unit, text);
        if (kind == PointKind.DAILY_ENERGY) {
            if (volume) {
                value = n.flowM3h() * 0.5 * hour;
            } else if (PointClassifier.isGeneration(text)) {
                value = n.pvKw() * 0.85 * DayCurves.sunHoursUntil(hour);
            } else {
                value = n.loadKw() * 0.6 * hour;
            }
        } else {
            value = volume ? 500 + random.nextDouble() * 4500 : 1000 + random.nextDouble() * 9000;
        }
        value *= unitScale();
        if (def.maxValue() != null) {
            value = Math.min(value, def.maxValue() * 0.5);
        }
        if (def.minValue() != null) {
            value = Math.max(value, def.minValue());
        }
        return value;
    }

    /** kWh 为基准的单位换算：MWh 0.001，Wh 1000，其余（kWh、m³、无单位）为 1。 */
    private double unitScale() {
        return switch (unit) {
            case "mwh" -> 0.001;
            case "wh" -> 1000;
            default -> 1;
        };
    }

    /** 枚举：每个（设备, 测点, 整点小时）有 85% 为正常值，其余取另一个枚举项；同一小时内不变。 */
    private Object enumValue(long epochMillis) {
        List<PropertyDef.EnumOption> items = def.enumItems();
        PropertyDef.EnumOption normal = normalOption(items);
        long hourIndex = Math.floorDiv(epochMillis, HOUR_MS);
        long draw = Math.floorMod(OperatingState.mix(seed * 0x9E3779B97F4A7C15L ^ hourIndex), 100L);
        if (draw < 85 || items.size() == 1) {
            return normal.value();
        }
        List<PropertyDef.EnumOption> others = items.stream().filter(i -> i != normal).toList();
        int pick = (int) Math.floorMod(OperatingState.mix(seed ^ (hourIndex * 0x9E3779B97F4A7C15L)),
                (long) others.size());
        return others.get(pick).value();
    }

    /** 布尔：每个（设备, 测点, 整点小时）有 5% 为 true。 */
    private Object boolValue(long epochMillis) {
        long hourIndex = Math.floorDiv(epochMillis, HOUR_MS);
        return Math.floorMod(OperatingState.mix((seed + 0x632BE59BD9B4E019L) * 0x9E3779B97F4A7C15L ^ hourIndex),
                100L) < 5;
    }

    /** 枚举的正常值：第一个标签含 运行/发电/开启/正常 的枚举项，否则第一个枚举项。 */
    static PropertyDef.EnumOption normalOption(List<PropertyDef.EnumOption> items) {
        for (PropertyDef.EnumOption item : items) {
            if (item.label() != null && PointClassifier.containsAny(item.label(), "运行", "发电", "开启", "正常")) {
                return item;
            }
        }
        return items.getFirst();
    }

    /** 额定值：负载 0.75×上限，光伏 0.9×上限，辐照 min(上限,1000)，流量 0.7×上限；无上限时按单位取默认值。 */
    private double nominal() {
        Double max = def.maxValue();
        boolean hasMax = max != null && max > 0;
        return switch (kind) {
            case LOAD -> hasMax ? max * 0.75 : switch (unit) {
                case "a" -> 50.0;
                case "w" -> 30_000.0;
                case "mw" -> 0.03;
                default -> 30.0;
            };
            case PV_POWER -> hasMax ? max * 0.9 : switch (unit) {
                case "w" -> 50_000.0;
                case "mw" -> 0.05;
                default -> 50.0;
            };
            case IRRADIANCE -> hasMax ? Math.min(max, 1000.0) : 1000.0;
            case FLOW -> hasMax ? max * 0.7 : 30.0;
            default -> 0.0;
        };
    }

    /** 三相电压的固定偏移：标识以 a/b/c 结尾时分别为 -0.8 / 0 / +0.8 V，其他按标识哈希取 -0.8～+0.8。 */
    private static double phaseOffset(String identifier) {
        char last = Character.toLowerCase(identifier.charAt(identifier.length() - 1));
        return switch (last) {
            case 'a' -> -0.8;
            case 'b' -> 0.0;
            case 'c' -> 0.8;
            default -> (Math.floorMod(identifier.hashCode(), 5) - 2) * 0.4;
        };
    }

    private double toKw(double value) {
        return switch (unit) {
            case "w" -> value / 1000;
            case "mw" -> value * 1000;
            default -> value;
        };
    }
}
