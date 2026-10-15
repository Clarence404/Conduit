package dev.conduit.server.presence;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceStoreIT extends IntegrationTestBase {

    @Autowired
    PresenceStore store;

    @Test
    void appliesNewerEventsAndIgnoresStaleOnes() {
        String dk = unique("dk");

        assertThat(store.apply(dk, true, 2000, "10.0.0.1")).isTrue();
        assertThat(store.apply(dk, false, 1000, "")).isFalse();

        assertThat(store.get(dk)).contains(new Presence(true, 2000, "10.0.0.1"));
    }

    @Test
    void laterDisconnectWins() {
        String dk = unique("dk");

        store.apply(dk, true, 1000, "10.0.0.1");
        store.apply(dk, false, 3000, "");

        assertThat(store.get(dk)).contains(new Presence(false, 3000, ""));
    }

    @Test
    void getAllReturnsOnlyKnownDevices() {
        String a = unique("dk");
        String b = unique("dk");
        store.apply(a, true, 1000, "ip");

        assertThat(store.getAll(List.of(a, b))).containsOnlyKeys(a);
    }

    @Test
    void deleteRemovesPresence() {
        String dk = unique("dk");
        store.apply(dk, true, 1000, "ip");

        store.delete(dk);

        assertThat(store.get(dk)).isEmpty();
    }

    @Test
    void onlineApplyOlderThanStoredOfflineIsIgnored() {
        String dk = unique("dk");
        store.apply(dk, false, 5000, "");

        assertThat(store.apply(dk, true, 4000, "")).isFalse();

        assertThat(store.get(dk)).contains(new Presence(false, 5000, ""));
    }

    @Test
    void onlineApplyNewerThanStoredOfflineSetsOnline() {
        String dk = unique("dk");
        store.apply(dk, false, 5000, "");

        assertThat(store.apply(dk, true, 6000, "")).isTrue();

        assertThat(store.get(dk).orElseThrow().online()).isTrue();
        assertThat(store.get(dk).orElseThrow().since()).isEqualTo(6000);
    }

    @Test
    void onlineApplyWithoutIpKeepsExistingIp() {
        String dk = unique("dk");
        store.apply(dk, true, 1000, "10.0.0.1");

        store.apply(dk, true, 2000, "");

        assertThat(store.get(dk)).contains(new Presence(true, 2000, "10.0.0.1"));
    }
}
