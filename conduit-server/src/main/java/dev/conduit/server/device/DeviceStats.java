package dev.conduit.server.device;

import dev.conduit.server.device.DeviceMapper.ProductStatusCount;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.presence.PresenceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按产品统计设备数、启用数与在线数：设备数与启用数来自 PostgreSQL 分组计数（租户拦截器过滤），
 * 在线数来自 Redis 在线状态（一次管道批量读取）。Redis 不可用时在线数为 null，其余照常。
 * 供产品列表/详情的「设备（在线/总数）」与首页总览使用。本期把统计范围内的设备全部读出，设备到万级时改为增量计数（见 backlog）。
 */
@Component
public class DeviceStats {

    private static final Logger log = LoggerFactory.getLogger(DeviceStats.class);

    private final DeviceMapper devices;
    private final PresenceStore presence;

    public DeviceStats(DeviceMapper devices, PresenceStore presence) {
        this.devices = devices;
        this.presence = presence;
    }

    /**
     * 一个产品的设备计数。
     *
     * @param deviceCount  设备总数
     * @param enabledCount 启用的设备数
     * @param onlineCount  在线设备数；Redis 不可用时为 null
     */
    public record ProductCounts(int deviceCount, int enabledCount, Integer onlineCount) {

        /** 没有设备的产品：在线数随 Redis 是否可用为 0 或 null。 */
        static ProductCounts empty(boolean presenceAvailable) {
            return new ProductCounts(0, 0, presenceAvailable ? 0 : null);
        }
    }

    /**
     * 一次统计的结果。
     *
     * @param devices    统计范围内的设备精简信息（id、productId、deviceKey、name、status）
     * @param byProduct  有设备的产品的计数，键为产品 id
     * @param onlineKeys 在线设备的 deviceKey；Redis 不可用时为 null
     */
    public record Snapshot(List<Device> devices, Map<Long, ProductCounts> byProduct, Set<String> onlineKeys) {

        /** 是否成功读到了在线状态。 */
        public boolean presenceAvailable() {
            return onlineKeys != null;
        }

        /** 指定产品的计数；该产品没有设备时为 0（在线数在 Redis 不可用时为 null）。 */
        public ProductCounts of(long productId) {
            return byProduct.getOrDefault(productId, ProductCounts.empty(presenceAvailable()));
        }

        /** 统计范围内的设备总数。 */
        public int deviceCount() {
            return byProduct.values().stream().mapToInt(ProductCounts::deviceCount).sum();
        }

        /** 统计范围内启用的设备数。 */
        public int enabledCount() {
            return byProduct.values().stream().mapToInt(ProductCounts::enabledCount).sum();
        }

        /** 统计范围内的在线设备数；Redis 不可用时为 null。 */
        public Integer onlineCount() {
            return onlineKeys == null ? null : onlineKeys.size();
        }
    }

    /**
     * 统计设备：PostgreSQL 分组计数得到每个产品的设备数与启用数，再批量读 Redis 在线状态得到在线数。
     *
     * @param productIds 限定的产品 id；null 表示当前租户全部产品
     * @return 统计结果；Redis 不可用时 onlineKeys 为 null、各产品在线数为 null
     */
    public Snapshot snapshot(Collection<Long> productIds) {
        List<ProductStatusCount> counts = devices.countByProductAndStatus(productIds);
        List<Device> brief = devices.selectBrief(productIds);
        Set<String> online = onlineKeys(brief);

        Map<Long, Integer> onlineByProduct = new HashMap<>();
        if (online != null) {
            for (Device d : brief) {
                if (online.contains(d.getDeviceKey())) {
                    onlineByProduct.merge(d.getProductId(), 1, Integer::sum);
                }
            }
        }
        Map<Long, int[]> totals = new HashMap<>();
        for (ProductStatusCount c : counts) {
            int[] t = totals.computeIfAbsent(c.productId(), id -> new int[2]);
            t[0] += c.count();
            if (Device.ENABLED.equals(c.status())) {
                t[1] += c.count();
            }
        }
        Map<Long, ProductCounts> byProduct = new HashMap<>();
        totals.forEach((productId, t) -> byProduct.put(productId, new ProductCounts(t[0], t[1],
                online == null ? null : onlineByProduct.getOrDefault(productId, 0))));
        return new Snapshot(brief, byProduct, online);
    }

    /**
     * 读取在线设备集合。没有设备时直接返回空集合（不访问 Redis）；Redis 不可用时记录告警并返回 null。
     */
    private Set<String> onlineKeys(List<Device> brief) {
        if (brief.isEmpty()) {
            return Set.of();
        }
        try {
            Map<String, Presence> states = presence.getAll(brief.stream().map(Device::getDeviceKey).toList());
            Set<String> online = new HashSet<>();
            states.forEach((deviceKey, p) -> {
                if (p.online()) {
                    online.add(deviceKey);
                }
            });
            return online;
        } catch (DataAccessException e) {
            log.warn("Presence store unavailable, online counts omitted", e);
            return null;
        }
    }
}
