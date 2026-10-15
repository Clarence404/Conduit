package dev.conduit.server.overview;

import dev.conduit.server.device.Device;
import dev.conduit.server.device.DeviceStats;
import dev.conduit.server.device.DeviceStats.ProductCounts;
import dev.conduit.server.overview.OverviewDtos.OverviewView;
import dev.conduit.server.overview.OverviewDtos.ProductStat;
import dev.conduit.server.overview.OverviewDtos.RecentDevice;
import dev.conduit.server.product.Product;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.thingmodel.ProductPropertyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OverviewService 单元测试：计数与两个列表、死信按租户产品过滤并按原因分组、Redis 不可用时相关字段为 null。 */
class OverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");

    private final ProductMapper products = mock(ProductMapper.class);
    private final ProductPropertyMapper properties = mock(ProductPropertyMapper.class);
    private final DeviceStats stats = mock(DeviceStats.class);
    private final LatestValueStore latest = mock(LatestValueStore.class);
    private final DeadLetterReader deadLetters = mock(DeadLetterReader.class);
    private final OverviewService service = new OverviewService(products, properties, stats, latest, deadLetters,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final List<Device> devices = List.of(
            device(1, "dk1", "1号楼配电室 电表01", "ENABLED"),
            device(1, "dk2", "1号楼配电室 电表02", "ENABLED"),
            device(2, "dk3", "1号楼 水表01", "DISABLED"));

    private static Product product(long id, String key, String name) {
        Product p = new Product();
        p.setId(id);
        p.setProductKey(key);
        p.setName(name);
        return p;
    }

    private static Device device(long productId, String key, String name, String status) {
        Device d = new Device();
        d.setProductId(productId);
        d.setDeviceKey(key);
        d.setName(name);
        d.setStatus(status);
        return d;
    }

    private static Map<String, String> dead(String productKey, String reason) {
        Map<String, String> record = new HashMap<>();
        if (productKey != null) {
            record.put("pk", productKey);
        }
        record.put("dk", "dk-x");
        record.put("reason", reason);
        return record;
    }

    @BeforeEach
    void setUp() {
        // id 倒序：水表（id 2）在前
        when(products.selectList(any())).thenReturn(List.of(product(2, "pk2", "水表（演示）"), product(1, "pk1", "三相电表（演示）")));
        when(properties.selectCount(any())).thenReturn(15L);
        when(stats.snapshot(isNull())).thenReturn(new DeviceStats.Snapshot(devices,
                Map.of(1L, new ProductCounts(2, 2, 1), 2L, new ProductCounts(1, 0, 0)), Set.of("dk1")));
        when(latest.lastReportTimes(anyList())).thenReturn(Map.of("dk1", 3000L, "dk3", 5000L));
        when(deadLetters.since(anyLong(), anyInt())).thenReturn(List.of(
                dead("pk1", "FIELD_REJECTED"), dead("pk1", "FIELD_REJECTED"), dead("pk2", "MALFORMED_JSON"),
                dead("pk-other-tenant", "PROCESSING_ERROR"), dead(null, "UNKNOWN_DEVICE")));
    }

    @Test
    void countsDevicesAndListsRecentDevicesAndProducts() {
        OverviewView v = service.overview();

        assertThat(v.productCount()).isEqualTo(2);
        assertThat(v.propertyCount()).isEqualTo(15);
        assertThat(v.deviceCount()).isEqualTo(3);
        assertThat(v.enabledCount()).isEqualTo(2);
        assertThat(v.disabledCount()).isEqualTo(1);
        assertThat(v.onlineCount()).isEqualTo(1);
        assertThat(v.recentDevices()).containsExactly(
                new RecentDevice("dk3", "1号楼 水表01", "pk2", "水表（演示）", 5000L),
                new RecentDevice("dk1", "1号楼配电室 电表01", "pk1", "三相电表（演示）", 3000L));
        assertThat(v.products()).containsExactly(
                new ProductStat("pk1", "三相电表（演示）", 2, 1),
                new ProductStat("pk2", "水表（演示）", 1, 0));
    }

    @Test
    void deadLettersAreFilteredByTenantProductsAndGroupedByReason() {
        OverviewView v = service.overview();

        assertThat(v.deadLastHour().total()).isEqualTo(3);
        assertThat(v.deadLastHour().byReason()).containsExactly(
                Map.entry("FIELD_REJECTED", 2), Map.entry("MALFORMED_JSON", 1));
        verify(deadLetters).since(NOW.toEpochMilli() - 3_600_000L, 10_000);
    }

    @Test
    void keepsAtMostFiveRecentDevicesNewestFirst() {
        List<Device> many = List.of(
                device(1, "a", "A", "ENABLED"), device(1, "b", "B", "ENABLED"), device(1, "c", "C", "ENABLED"),
                device(1, "d", "D", "ENABLED"), device(1, "e", "E", "ENABLED"), device(1, "f", "F", "ENABLED"));
        when(stats.snapshot(isNull())).thenReturn(new DeviceStats.Snapshot(many,
                Map.of(1L, new ProductCounts(6, 6, 0)), Set.of()));
        when(latest.lastReportTimes(anyList())).thenReturn(Map.of("a", 1L, "b", 6L, "c", 3L, "d", 5L, "e", 2L, "f", 4L));

        assertThat(service.overview().recentDevices()).extracting(RecentDevice::deviceKey)
                .containsExactly("b", "d", "f", "c", "e");
    }

    @Test
    void redisUnavailableNullsPresenceFieldsButKeepsTheRest() {
        when(stats.snapshot(isNull())).thenReturn(new DeviceStats.Snapshot(devices,
                Map.of(1L, new ProductCounts(2, 2, null), 2L, new ProductCounts(1, 0, null)), null));
        when(latest.lastReportTimes(anyList())).thenThrow(new RedisConnectionFailureException("down"));
        when(deadLetters.since(anyLong(), anyInt())).thenThrow(new RedisConnectionFailureException("down"));

        OverviewView v = service.overview();

        assertThat(v.onlineCount()).isNull();
        assertThat(v.products()).extracting(ProductStat::onlineCount).containsOnlyNulls();
        assertThat(v.recentDevices()).isEmpty();
        assertThat(v.deadLastHour()).isNull();
        assertThat(v.deviceCount()).isEqualTo(3);
        assertThat(v.enabledCount()).isEqualTo(2);
        assertThat(v.productCount()).isEqualTo(2);
        assertThat(v.propertyCount()).isEqualTo(15);
    }

    @Test
    void noDeadLettersGiveAnEmptySummary() {
        when(deadLetters.since(anyLong(), anyInt())).thenReturn(List.of());

        assertThat(service.overview().deadLastHour()).isEqualTo(new OverviewDtos.DeadSummary(0, Map.of()));
    }
}
