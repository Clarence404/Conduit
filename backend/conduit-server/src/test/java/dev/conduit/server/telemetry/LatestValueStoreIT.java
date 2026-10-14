package dev.conduit.server.telemetry;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LatestValueStoreIT extends IntegrationTestBase {

    @Autowired
    LatestValueStore store;

    @Test
    void newerValuesOverwriteAndOlderValuesAreIgnoredPerField() {
        String dk = unique("dk");

        store.update(dk, 2000, Map.of("Ua", 220.0, "Ia", 5.0));
        store.update(dk, 1000, Map.of("Ua", 100.0, "Pf", 0.9));
        store.update(dk, 3000, Map.of("Ia", 6.0));

        assertThat(store.get(dk)).containsExactly(
                Map.entry("Ia", new LatestValue(6.0, 3000)),
                Map.entry("Pf", new LatestValue(0.9, 1000)),
                Map.entry("Ua", new LatestValue(220.0, 2000)));
    }

    @Test
    void sameTimestampOverwritesIdempotently() {
        String dk = unique("dk");

        store.update(dk, 1000, Map.of("Ua", 220.0));
        store.update(dk, 1000, Map.of("Ua", 220.0));

        assertThat(store.get(dk)).containsExactly(Map.entry("Ua", new LatestValue(220.0, 1000)));
    }

    @Test
    void keepsStringAndBooleanValues() {
        String dk = unique("dk");

        store.update(dk, 1000, Map.of("mode", "auto", "alarm", true));

        assertThat(store.get(dk)).containsEntry("mode", new LatestValue("auto", 1000))
                .containsEntry("alarm", new LatestValue(true, 1000));
    }

    @Test
    void lastReportTimeIsMaxTimestampSeen() {
        String a = unique("dk");
        String b = unique("dk");
        String none = unique("dk");

        store.update(a, 2000, Map.of("Ua", 1.0));
        store.update(a, 1000, Map.of("Ua", 1.0));
        store.update(b, 5000, Map.of("Ua", 1.0));

        assertThat(store.lastReportTimes(List.of(a, b, none)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(a, 2000L, b, 5000L));
    }

    @Test
    void deleteRemovesEverything() {
        String dk = unique("dk");
        store.update(dk, 1000, Map.of("Ua", 1.0));

        store.delete(dk);

        assertThat(store.get(dk)).isEmpty();
        assertThat(store.lastReportTimes(List.of(dk))).isEmpty();
    }
}
