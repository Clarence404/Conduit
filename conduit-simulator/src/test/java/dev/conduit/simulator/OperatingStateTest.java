package dev.conduit.simulator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** OperatingState 的单元测试：取值范围、小时内不变、确定性、约 80% 为运行。 */
class OperatingStateTest {

    static final long HOUR = 3_600_000L;
    static final long BASE = 1760400000000L; // 2025-10-14T00:00:00Z，整点

    @Test
    void onlyProducesKnownStates() {
        for (int d = 0; d < 50; d++) {
            for (int h = 0; h < 48; h++) {
                assertThat(OperatingState.at("dk-" + d, BASE + h * HOUR))
                        .isIn(OperatingState.STOPPED, OperatingState.RUNNING, OperatingState.MAINTENANCE);
            }
        }
    }

    @Test
    void isConstantWithinAnHourAndDeterministic() {
        for (int d = 0; d < 50; d++) {
            String dk = "dk-" + d;
            for (int h = 0; h < 24; h++) {
                long start = BASE + h * HOUR;
                long state = OperatingState.at(dk, start);
                assertThat(OperatingState.at(dk, start + 1_234_567)).isEqualTo(state);
                assertThat(OperatingState.at(dk, start + HOUR - 1)).isEqualTo(state);
            }
        }
    }

    @Test
    void isMostlyRunning() {
        int total = 0;
        int running = 0;
        int stopped = 0;
        for (int d = 0; d < 100; d++) {
            for (int h = 0; h < 200; h++) {
                long state = OperatingState.at("dk-" + d, BASE + h * HOUR);
                total++;
                if (state == OperatingState.RUNNING) {
                    running++;
                } else if (state == OperatingState.STOPPED) {
                    stopped++;
                }
            }
        }
        assertThat((double) running / total).isBetween(0.77, 0.83);
        assertThat((double) stopped / total).isBetween(0.07, 0.13);
        assertThat((double) (total - running - stopped) / total).isBetween(0.07, 0.13);
    }

    @Test
    void changesOnlyAtHourBoundariesAndSometimesDoes() {
        int changes = 0;
        for (int d = 0; d < 100; d++) {
            for (int h = 1; h < 24; h++) {
                if (OperatingState.at("dk-" + d, BASE + h * HOUR) != OperatingState.at("dk-" + d, BASE + h * HOUR - 1)) {
                    changes++;
                }
            }
        }
        assertThat(changes).isPositive().isLessThan(100 * 23);
    }
}
