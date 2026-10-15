package dev.conduit.server.device;

import dev.conduit.server.device.DeviceMapper.ProductStatusCount;
import dev.conduit.server.device.DeviceStats.ProductCounts;
import dev.conduit.server.device.DeviceStats.Snapshot;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.presence.PresenceStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DeviceStats 单元测试：按产品统计设备数、启用数与在线数；Redis 不可用时在线数为 null；空产品集合不读 Redis。 */
class DeviceStatsTest {

    private final DeviceMapper devices = mock(DeviceMapper.class);
    private final PresenceStore presence = mock(PresenceStore.class);
    private final DeviceStats stats = new DeviceStats(devices, presence);

    private static Device device(long productId, String deviceKey) {
        Device d = new Device();
        d.setProductId(productId);
        d.setDeviceKey(deviceKey);
        d.setName("设备 " + deviceKey);
        d.setStatus(Device.ENABLED);
        return d;
    }

    private void givenTwoProducts() {
        when(devices.countByProductAndStatus(isNull())).thenReturn(List.of(
                new ProductStatusCount(1, "ENABLED", 2),
                new ProductStatusCount(1, "DISABLED", 1),
                new ProductStatusCount(2, "ENABLED", 1)));
        when(devices.selectBrief(isNull())).thenReturn(List.of(
                device(1, "a"), device(1, "b"), device(1, "c"), device(2, "d")));
    }

    @Test
    void countsDevicesEnabledAndOnlinePerProduct() {
        givenTwoProducts();
        when(presence.getAll(List.of("a", "b", "c", "d"))).thenReturn(Map.of(
                "a", new Presence(true, 1, ""),
                "b", new Presence(false, 1, ""),
                "d", new Presence(true, 1, "")));

        Snapshot s = stats.snapshot(null);

        assertThat(s.of(1)).isEqualTo(new ProductCounts(3, 2, 1));
        assertThat(s.of(2)).isEqualTo(new ProductCounts(1, 1, 1));
        assertThat(s.of(99)).isEqualTo(new ProductCounts(0, 0, 0));
        assertThat(s.deviceCount()).isEqualTo(4);
        assertThat(s.enabledCount()).isEqualTo(3);
        assertThat(s.onlineCount()).isEqualTo(2);
        assertThat(s.presenceAvailable()).isTrue();
        assertThat(s.devices()).hasSize(4);
    }

    @Test
    void redisUnavailableLeavesOnlineCountsNullButKeepsDeviceCounts() {
        givenTwoProducts();
        when(presence.getAll(anyList())).thenThrow(new RedisConnectionFailureException("down"));

        Snapshot s = stats.snapshot(null);

        assertThat(s.presenceAvailable()).isFalse();
        assertThat(s.of(1)).isEqualTo(new ProductCounts(3, 2, null));
        assertThat(s.of(99)).isEqualTo(new ProductCounts(0, 0, null));
        assertThat(s.onlineCount()).isNull();
        assertThat(s.deviceCount()).isEqualTo(4);
    }

    @Test
    void productsWithoutDevicesDoNotTouchRedis() {
        when(devices.countByProductAndStatus(List.of(7L))).thenReturn(List.of());
        when(devices.selectBrief(List.of(7L))).thenReturn(List.of());

        Snapshot s = stats.snapshot(List.of(7L));

        assertThat(s.of(7)).isEqualTo(new ProductCounts(0, 0, 0));
        assertThat(s.onlineCount()).isZero();
        verify(presence, never()).getAll(anyList());
    }
}
