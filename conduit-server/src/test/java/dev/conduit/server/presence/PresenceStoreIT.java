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
    void eventOlderThanStoredOfflineIsIgnored() {
        String dk = unique("dk");
        store.apply(dk, false, 5000, "");

        assertThat(store.apply(dk, true, 4000, "")).isFalse();

        assertThat(store.get(dk)).contains(new Presence(false, 5000, ""));
    }

    @Test
    void onlineEventWithoutIpKeepsExistingIp() {
        String dk = unique("dk");
        store.apply(dk, true, 1000, "10.0.0.1");

        store.apply(dk, true, 2000, "");

        assertThat(store.get(dk)).contains(new Presence(true, 2000, "10.0.0.1"));
    }

    @Test
    void dataForUnknownDeviceMarksOnline() {
        String dk = unique("dk");

        assertThat(store.applyData(dk, 7000)).isTrue();

        assertThat(store.get(dk)).contains(new Presence(true, 7000, ""));
    }

    @Test
    void dataWhileOnlineDoesNotMoveSinceOrIp() {
        String dk = unique("dk");
        store.apply(dk, true, 1000, "10.0.0.1");

        assertThat(store.applyData(dk, 5000)).isFalse();

        assertThat(store.get(dk)).contains(new Presence(true, 1000, "10.0.0.1"));
    }

    /** (a) 数据不推进 since：晚到的、事件时间早于数据时间的断连仍然生效。 */
    @Test
    void disconnectOlderThanEarlierDataStillGoesOffline() {
        String dk = unique("dk");
        store.apply(dk, true, 1000, "10.0.0.1");
        store.applyData(dk, 5000);

        assertThat(store.apply(dk, false, 3000, "")).isTrue();

        assertThat(store.get(dk)).contains(new Presence(false, 3000, ""));
    }

    /** 没有记录时由数据推断上线，之后事件时间更早的断连也不能被数据时钟挡掉。 */
    @Test
    void disconnectOlderThanDataInferredOnlineStillGoesOffline() {
        String dk = unique("dk");
        store.applyData(dk, 5000);

        assertThat(store.apply(dk, false, 3000, "")).isTrue();

        assertThat(store.get(dk).orElseThrow().online()).isFalse();
    }

    /** (b) 断连之后宽限期内（含边界）的数据不会让设备复活。 */
    @Test
    void dataWithinGraceAfterOfflineDoesNotResurrect() {
        String dk = unique("dk");
        store.apply(dk, false, 5000, "");

        assertThat(store.applyData(dk, 5001)).isFalse();
        assertThat(store.applyData(dk, 5000 + PresenceStore.DATA_ONLINE_GRACE_MILLIS)).isFalse();

        assertThat(store.get(dk)).contains(new Presence(false, 5000, ""));
    }

    /** (c) 超过宽限期的数据让离线设备重新上线，since 取该数据时间。 */
    @Test
    void dataAfterGraceResurrects() {
        String dk = unique("dk");
        store.apply(dk, false, 5000, "");
        long ts = 5000 + PresenceStore.DATA_ONLINE_GRACE_MILLIS + 1;

        assertThat(store.applyData(dk, ts)).isTrue();

        assertThat(store.get(dk)).contains(new Presence(true, ts, ""));
    }

    @Test
    void applyDataBatchMarksEachDeviceInOneCall() {
        String a = unique("dk");
        String b = unique("dk");
        store.apply(b, true, 1000, "10.0.0.2");

        store.applyDataBatch(java.util.Map.of(a, 4000L, b, 6000L));

        assertThat(store.get(a)).contains(new Presence(true, 4000, ""));
        assertThat(store.get(b)).contains(new Presence(true, 1000, "10.0.0.2"));
    }
}
