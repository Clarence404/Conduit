package dev.conduit.simulator;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ThingModelReadingSource 单元测试：光伏夜间为 0、累计量单调递增且与功率相符、日发电量零点归零、电压三相略有差别、
 * 设备温度高于环境温度、枚举多数时间为正常值且一小时内不变、布尔低概率为是、TEXT 跳过、所有取值满足物模型约束。
 */
class ThingModelReadingSourceTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static PropertyDef def(String id, String name, String type, String unit, Double min, Double max,
                                   Integer decimals) {
        return new PropertyDef(id, name, type, unit, min, max, decimals, List.of());
    }

    private static PropertyDef.EnumOption opt(long value, String label) {
        return new PropertyDef.EnumOption(value, label);
    }

    private static long at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    @Test
    void pvPowerIsZeroAtNightAndPositiveAtNoon() {
        var source = new ThingModelReadingSource(
                List.of(def("Ppv", "光伏功率", "DOUBLE", "kW", 0.0, 100.0, 2)), "pv-1", ZONE);

        for (long t : new long[]{at(16, 0, 30), at(16, 5, 30), at(16, 18, 0), at(16, 22, 15)}) {
            assertThat(source.next(t, 60).get("Ppv")).isEqualTo(0.0);
        }
        assertThat((Double) source.next(at(16, 12, 0), 60).get("Ppv")).isBetween(40.0, 100.0);
    }

    @Test
    void energyIsMonotonicAndFollowsThePower() {
        var source = new ThingModelReadingSource(List.of(
                def("P", "有功功率", "DOUBLE", "kW", 0.0, 60.0, 2),
                def("EPimp", "正向有功电能", "DOUBLE", "kWh", null, null, 3)), "meter-1", ZONE);

        double first = 0;
        double last = 0;
        double expected = 0;
        for (int i = 0; i < 600; i++) {
            Map<String, Object> reading = source.next(at(16, 8, 0) + i * 60_000L, 60);
            double energy = (Double) reading.get("EPimp");
            if (i == 0) {
                first = energy;
            } else {
                assertThat(energy).isGreaterThanOrEqualTo(last);
                expected += (Double) reading.get("P") / 60;
            }
            last = energy;
        }
        assertThat(expected).isGreaterThan(100);
        assertThat(last - first).isCloseTo(expected, within(expected * 0.01 + 0.01));
    }

    @Test
    void waterTotalIsMonotonic() {
        var source = new ThingModelReadingSource(List.of(
                def("flow", "瞬时流量", "DOUBLE", "m³/h", 0.0, 200.0, 2),
                def("total", "累计用水量", "DOUBLE", "m³", null, null, 2)), "water-1", ZONE);

        double last = -1;
        for (int i = 0; i < 2000; i++) {
            double total = (Double) source.next(at(16, 0, 0) + i * 300_000L, 300).get("total");
            assertThat(total).isGreaterThanOrEqualTo(last);
            last = total;
        }
    }

    @Test
    void dailyEnergyResetsAtMidnightAndStaysZeroUntilSunrise() {
        var source = new ThingModelReadingSource(List.of(
                def("Ppv", "光伏功率", "DOUBLE", "kW", 0.0, 100.0, 2),
                def("eday", "日发电量", "DOUBLE", "kWh", 0.0, 2000.0, 2)), "inv-1", ZONE);

        double previous = -1;
        for (long t = at(16, 10, 0); t < at(17, 0, 0); t += 600_000L) {
            double value = (Double) source.next(t, 600).get("eday");
            assertThat(value).isGreaterThanOrEqualTo(previous);
            previous = value;
        }
        assertThat(previous).isGreaterThan(0);
        assertThat(source.next(at(17, 0, 0), 600).get("eday")).isEqualTo(0.0);
        for (long t = at(17, 0, 10); t <= at(17, 5, 50); t += 600_000L) {
            assertThat(source.next(t, 600).get("eday")).isEqualTo(0.0);
        }
        for (long t = at(17, 6, 0); t <= at(17, 12, 0); t += 600_000L) {
            source.next(t, 600);
        }
        assertThat((Double) source.next(at(17, 12, 10), 600).get("eday")).isGreaterThan(0);
    }

    @Test
    void voltagesStayNear220WithSmallPhaseDifferences() {
        var source = new ThingModelReadingSource(List.of(
                def("Ua", "A 相电压", "DOUBLE", "V", null, null, 1),
                def("Ub", "B 相电压", "DOUBLE", "V", null, null, 1),
                def("Uc", "C 相电压", "DOUBLE", "V", null, null, 1)), "meter-2", ZONE);

        double sumA = 0;
        double sumB = 0;
        double sumC = 0;
        for (int i = 0; i < 500; i++) {
            Map<String, Object> reading = source.next(at(16, 0, 0) + i * 60_000L, 60);
            for (String phase : new String[]{"Ua", "Ub", "Uc"}) {
                assertThat((Double) reading.get(phase)).isBetween(215.0, 225.0);
            }
            sumA += (Double) reading.get("Ua");
            sumB += (Double) reading.get("Ub");
            sumC += (Double) reading.get("Uc");
        }
        assertThat(sumA / 500).isLessThan(sumB / 500);
        assertThat(sumB / 500).isLessThan(sumC / 500);
    }

    @Test
    void deviceTemperatureIsAboveTheAmbientTemperature() {
        var source = new ThingModelReadingSource(List.of(
                def("ta", "环境温度", "DOUBLE", "℃", -40.0, 60.0, 1),
                def("tw", "绕组温度", "DOUBLE", "℃", -40.0, 150.0, 1)), "trafo-1", ZONE);

        Map<String, Object> evening = source.next(at(16, 20, 0), 60);

        assertThat((Double) evening.get("tw") - (Double) evening.get("ta")).isGreaterThan(10.0);
        assertThat((Double) evening.get("ta")).isBetween(10.0, 26.0);
    }

    @Test
    void enumsUseTheNormalValueMostOfTheTimeAndHoldItForAnHour() {
        var source = new ThingModelReadingSource(List.of(new PropertyDef("st", "运行状态", "ENUM", null, null, null,
                null, List.of(opt(0, "停机"), opt(1, "运行"), opt(2, "检修")))), "inv-2", ZONE);

        int normal = 0;
        for (int h = 0; h < 1000; h++) {
            long hourStart = at(16, 0, 0) + h * 3_600_000L;
            Object first = source.next(hourStart, 60).get("st");
            for (int m = 1; m < 60; m += 7) {
                assertThat(source.next(hourStart + m * 60_000L, 60).get("st")).isEqualTo(first);
            }
            if (Long.valueOf(1).equals(first)) {
                normal++;
            }
        }
        assertThat(normal).isGreaterThan(750);
    }

    @Test
    void theFirstItemIsNormalWhenNoLabelLooksNormal() {
        assertThat(PointGenerator.normalOption(List.of(opt(5, "低速档"), opt(6, "高速档"))).value()).isEqualTo(5);
        assertThat(PointGenerator.normalOption(List.of(opt(0, "关闭"), opt(1, "开启"))).value()).isEqualTo(1);
    }

    @Test
    void booleansAreRarelyTrue() {
        var source = new ThingModelReadingSource(List.of(new PropertyDef("alarm", "告警", "BOOL", null, null,
                null, null, List.of())), "env-1", ZONE);

        int trues = 0;
        for (int h = 0; h < 2000; h++) {
            if (Boolean.TRUE.equals(source.next(at(16, 0, 0) + h * 3_600_000L, 60).get("alarm"))) {
                trues++;
            }
        }
        assertThat(trues).isBetween(20, 240);
    }

    @Test
    void textPropertiesAreSkipped() {
        var source = new ThingModelReadingSource(List.of(
                def("note", "备注", "TEXT", null, null, null, null),
                def("Ua", "A 相电压", "DOUBLE", "V", null, null, 1)), "meter-3", ZONE);

        assertThat(source.pointCount()).isEqualTo(1);
        assertThat(source.next(at(16, 9, 0), 60)).containsOnlyKeys("Ua");
    }

    @Test
    void everyValueSatisfiesTheThingModelConstraints() {
        List<PropertyDef> model = List.of(
                def("Ua", "A 相电压", "DOUBLE", "V", 200.0, 240.0, 1),
                def("Ia", "A 相电流", "DOUBLE", "A", 0.0, 80.0, 2),
                def("P", "有功功率", "DOUBLE", "kW", 0.0, 60.0, 2),
                def("Ppv", "光伏功率", "DOUBLE", "kW", 0.0, 100.0, 2),
                def("irr", "辐照度", "DOUBLE", "W/m²", 0.0, 1200.0, 0),
                def("EPimp", "正向有功电能", "DOUBLE", "kWh", null, null, 3),
                def("eday", "日发电量", "DOUBLE", "kWh", 0.0, 1000.0, 2),
                def("flow", "瞬时流量", "DOUBLE", "m³/h", 0.0, 200.0, 2),
                def("total", "累计用水量", "DOUBLE", "m³", null, null, 2),
                def("tw", "绕组温度", "DOUBLE", "℃", -20.0, 120.0, 1),
                def("press", "出水压力", "DOUBLE", "MPa", 0.0, 1.0, 3),
                def("ph", "pH", "DOUBLE", null, 0.0, 14.0, 2),
                def("freq", "频率", "DOUBLE", "Hz", null, null, 2),
                def("count", "启动次数", "INT", null, 10.0, 20.0, null),
                new PropertyDef("st", "运行状态", "ENUM", null, null, null, null,
                        List.of(opt(0, "停机"), opt(1, "运行"), opt(2, "检修"))),
                new PropertyDef("alarm", "告警", "BOOL", null, null, null, null, List.of()),
                def("note", "备注", "TEXT", null, null, null, null));
        var source = new ThingModelReadingSource(model, "mixed-1", ZONE);

        for (int i = 0; i < 2016; i++) {
            Map<String, Object> reading = source.next(at(16, 0, 0) + i * 300_000L, 300);
            for (PropertyDef p : model) {
                Object v = reading.get(p.identifier());
                if (p.dataType().equals("TEXT")) {
                    assertThat(reading).doesNotContainKey(p.identifier());
                    continue;
                }
                assertThat(v).as(p.identifier()).isNotNull();
                switch (p.dataType()) {
                    case "DOUBLE" -> {
                        assertThat(v).as(p.identifier()).isInstanceOf(Double.class);
                        double d = (Double) v;
                        if (p.minValue() != null) {
                            assertThat(d).as(p.identifier()).isGreaterThanOrEqualTo(p.minValue());
                        }
                        if (p.maxValue() != null) {
                            assertThat(d).as(p.identifier()).isLessThanOrEqualTo(p.maxValue());
                        }
                        double scaled = d * Math.pow(10, p.decimals() == null ? 3 : p.decimals());
                        assertThat(Math.abs(scaled - Math.rint(scaled))).as(p.identifier() + " decimals")
                                .isLessThan(1e-6);
                    }
                    case "INT" -> {
                        assertThat(v).as(p.identifier()).isInstanceOf(Long.class);
                        assertThat((Long) v).isBetween(10L, 20L);
                    }
                    case "ENUM" -> assertThat(List.of(0L, 1L, 2L)).contains((Long) v);
                    case "BOOL" -> assertThat(v).isInstanceOf(Boolean.class);
                    default -> throw new AssertionError(p.dataType());
                }
            }
        }
    }
}
