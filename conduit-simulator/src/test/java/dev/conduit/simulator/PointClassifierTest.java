package dev.conduit.simulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PointClassifier 单元测试：按类型、标识、名称与单位把常见测点归入 spec 5.2 的规则类别。 */
class PointClassifierTest {

    private static PropertyDef def(String id, String name, String type, String unit) {
        return new PropertyDef(id, name, type, unit, null, null, null, List.of());
    }

    private static PointKind kind(String id, String name, String type, String unit) {
        return PointClassifier.classify(def(id, name, type, unit));
    }

    @Test
    void classifiesElectricalPoints() {
        assertThat(kind("Ua", "A 相电压", "DOUBLE", "V")).isEqualTo(PointKind.VOLTAGE);
        assertThat(kind("Uab", "线电压", "DOUBLE", null)).isEqualTo(PointKind.VOLTAGE);
        assertThat(kind("Ia", "A 相电流", "DOUBLE", "A")).isEqualTo(PointKind.LOAD);
        assertThat(kind("P", "有功功率", "DOUBLE", "kW")).isEqualTo(PointKind.LOAD);
        assertThat(kind("Pac", "交流功率", "DOUBLE", "W")).isEqualTo(PointKind.LOAD);
        assertThat(kind("Ppv", "光伏功率", "DOUBLE", "kW")).isEqualTo(PointKind.PV_POWER);
        assertThat(kind("pdc", "直流发电功率", "DOUBLE", "W")).isEqualTo(PointKind.PV_POWER);
        assertThat(kind("irr", "辐照度", "DOUBLE", "W/m²")).isEqualTo(PointKind.IRRADIANCE);
    }

    @Test
    void classifiesCumulativeTotals() {
        assertThat(kind("EPimp", "正向有功电能", "DOUBLE", "kWh")).isEqualTo(PointKind.CUMULATIVE);
        assertThat(kind("EPexp", "反向有功电能", "DOUBLE", null)).isEqualTo(PointKind.CUMULATIVE);
        assertThat(kind("total", "累计用水量", "DOUBLE", "m³")).isEqualTo(PointKind.CUMULATIVE);
        assertThat(kind("etotal", "累计发电量", "DOUBLE", "MWh")).isEqualTo(PointKind.CUMULATIVE);
        assertThat(kind("eday", "日发电量", "DOUBLE", "kWh")).isEqualTo(PointKind.DAILY_ENERGY);
        assertThat(kind("TEMP", "温度", "DOUBLE", "℃")).as("EP inside TEMP is not a total")
                .isEqualTo(PointKind.TEMPERATURE);
    }

    @Test
    void classifiesEnvironmentAndWaterPoints() {
        assertThat(kind("tw", "绕组温度", "DOUBLE", "°C")).isEqualTo(PointKind.TEMPERATURE);
        assertThat(kind("flow", "瞬时流量", "DOUBLE", "m³/h")).isEqualTo(PointKind.FLOW);
        assertThat(kind("press", "出水压力", "DOUBLE", "MPa")).isEqualTo(PointKind.DRIFT);
        assertThat(kind("ph", "pH", "DOUBLE", null)).isEqualTo(PointKind.DRIFT);
        assertThat(kind("turb", "浊度", "DOUBLE", "NTU")).isEqualTo(PointKind.DRIFT);
        assertThat(kind("cl", "余氯", "DOUBLE", "mg/L")).isEqualTo(PointKind.DRIFT);
        assertThat(kind("hum", "湿度", "DOUBLE", "%RH")).isEqualTo(PointKind.DRIFT);
        assertThat(kind("pm25", "PM2.5", "DOUBLE", "μg/m³")).isEqualTo(PointKind.DRIFT);
        assertThat(kind("co2", "CO₂ 浓度", "INT", "ppm")).isEqualTo(PointKind.DRIFT);
        assertThat(kind("ws", "风速", "DOUBLE", "m/s")).isEqualTo(PointKind.DRIFT);
        assertThat(kind("freq", "频率", "DOUBLE", "Hz")).isEqualTo(PointKind.OTHER);
    }

    @Test
    void classifiesStatesAndSkipsText() {
        PropertyDef state = new PropertyDef("st", "运行状态", "ENUM", null, null, null, null,
                List.of(new PropertyDef.EnumOption(0, "停机"), new PropertyDef.EnumOption(1, "运行")));
        assertThat(PointClassifier.classify(state)).isEqualTo(PointKind.ENUM);
        assertThat(kind("st", "空枚举", "ENUM", null)).isEqualTo(PointKind.SKIP);
        assertThat(kind("alarm", "告警", "BOOL", null)).isEqualTo(PointKind.BOOL);
        assertThat(kind("note", "备注", "TEXT", null)).isEqualTo(PointKind.SKIP);
        assertThat(PointClassifier.simulated(def("note", "备注", "TEXT", null))).isFalse();
        assertThat(PointClassifier.simulated(def("Ua", "A 相电压", "DOUBLE", "V"))).isTrue();
    }
}
