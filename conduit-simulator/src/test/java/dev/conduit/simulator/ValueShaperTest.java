package dev.conduit.simulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ValueShaper 单元测试：按小数位四舍五入、夹在上下限内（舍入后不越界）、INT 为 Long。 */
class ValueShaperTest {

    private static PropertyDef def(String type, Double min, Double max, Integer decimals) {
        return new PropertyDef("x", "x", type, null, min, max, decimals, List.of());
    }

    @Test
    void roundsToTheDeclaredDecimals() {
        assertThat(ValueShaper.shape(def("DOUBLE", null, null, 1), 220.149)).isEqualTo(220.1);
        assertThat(ValueShaper.shape(def("DOUBLE", null, null, 0), 845.6)).isEqualTo(846.0);
        assertThat(ValueShaper.shape(def("DOUBLE", null, null, null), 1.23456)).isEqualTo(1.235);
    }

    @Test
    void clampsToTheBoundsAndStaysInsideAfterRounding() {
        assertThat(ValueShaper.shape(def("DOUBLE", 0.0, 100.0, 2), 130.0)).isEqualTo(100.0);
        assertThat(ValueShaper.shape(def("DOUBLE", 0.0, 100.0, 2), -3.0)).isEqualTo(0.0);
        assertThat(ValueShaper.shape(def("DOUBLE", 0.0, 10.05, 1), 10.06)).isEqualTo(10.0);
        assertThat(ValueShaper.shape(def("DOUBLE", 0.05, 1.0, 1), 0.0)).isEqualTo(0.1);
    }

    @Test
    void intValuesAreLongsInsideTheIntegerBounds() {
        assertThat(ValueShaper.shape(def("INT", 10.0, 20.0, null), 14.6)).isEqualTo(15L);
        assertThat(ValueShaper.shape(def("INT", 10.5, 20.0, null), 3.0)).isEqualTo(11L);
        assertThat(ValueShaper.shape(def("INT", 10.0, 19.5, null), 25.0)).isEqualTo(19L);
    }
}
