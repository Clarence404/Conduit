package dev.conduit.simulator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** DayCurves 单元测试：日照只在 6～18 点为正、负荷与用水有早晚高峰、环境温度昼夜变化、日照积分。 */
class DayCurvesTest {

    @Test
    void sunshineIsZeroOutsideSixToEighteenAndPeaksAtNoon() {
        for (double hour : new double[]{0, 3, 5.99, 6, 18, 20.5, 23.9}) {
            assertThat(DayCurves.sun(hour)).as("hour %s", hour).isZero();
        }
        assertThat(DayCurves.sun(12)).isCloseTo(1.0, within(1e-9));
        assertThat(DayCurves.sun(9)).isBetween(0.6, 0.8);
    }

    @Test
    void loadIsLowAtNightAndHighInTheEvening() {
        for (double hour = 0; hour < 24; hour += 0.25) {
            assertThat(DayCurves.loadFactor(hour)).isBetween(0.25, 1.0);
        }
        assertThat(DayCurves.loadFactor(20)).isGreaterThan(0.8);
        assertThat(DayCurves.loadFactor(10.5)).isGreaterThan(0.7);
        assertThat(DayCurves.loadFactor(3.5)).isLessThan(0.3);
    }

    @Test
    void waterFlowPeaksInTheMorningAndEvening() {
        for (double hour = 0; hour < 24; hour += 0.25) {
            assertThat(DayCurves.flowFactor(hour)).isBetween(0.15, 1.0);
        }
        assertThat(DayCurves.flowFactor(8)).isGreaterThan(DayCurves.flowFactor(14));
        assertThat(DayCurves.flowFactor(19.5)).isGreaterThan(DayCurves.flowFactor(14));
        assertThat(DayCurves.flowFactor(3)).isLessThan(DayCurves.flowFactor(14));
    }

    @Test
    void ambientTemperatureIsCoolestBeforeDawnAndWarmestInTheAfternoon() {
        assertThat(DayCurves.ambientTemperature(3)).isCloseTo(11.0, within(1e-9));
        assertThat(DayCurves.ambientTemperature(15)).isCloseTo(25.0, within(1e-9));
    }

    @Test
    void sunHoursIntegrateTheSunshineCurve() {
        assertThat(DayCurves.sunHoursUntil(5)).isZero();
        assertThat(DayCurves.sunHoursUntil(12)).isCloseTo(12 / Math.PI, within(1e-9));
        assertThat(DayCurves.sunHoursUntil(23)).isCloseTo(24 / Math.PI, within(1e-9));
    }
}
