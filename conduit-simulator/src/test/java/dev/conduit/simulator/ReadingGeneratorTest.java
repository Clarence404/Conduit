package dev.conduit.simulator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingGeneratorTest {

    @Test
    void producesAllMeterFields() {
        assertThat(new ReadingGenerator(1).next(10)).containsOnlyKeys("Ua", "Ub", "Uc", "Ia", "Ib", "Ic", "EPimp");
    }

    @Test
    void energyIsStrictlyIncreasing() {
        ReadingGenerator gen = new ReadingGenerator(1);
        double previous = (double) gen.next(10).get("EPimp");
        for (int i = 0; i < 100; i++) {
            double current = (double) gen.next(10).get("EPimp");
            assertThat(current).isGreaterThan(previous);
            previous = current;
        }
    }

    @Test
    void valuesStayInPlausibleRanges() {
        ReadingGenerator gen = new ReadingGenerator(42);
        for (int i = 0; i < 1000; i++) {
            Map<String, Object> r = gen.next(10);
            for (String u : new String[]{"Ua", "Ub", "Uc"}) {
                assertThat((double) r.get(u)).isBetween(210.0, 230.0);
            }
            for (String c : new String[]{"Ia", "Ib", "Ic"}) {
                assertThat((double) r.get(c)).isBetween(0.0, 10.0);
            }
        }
    }

    @Test
    void sameSeedGivesSameSequence() {
        assertThat(new ReadingGenerator(7).next(10)).isEqualTo(new ReadingGenerator(7).next(10));
    }
}
