package dev.conduit.server.overview;

import java.util.List;
import java.util.Map;

/**
 * 首页总览接口（GET /api/v1/overview）的响应对象集合。在线相关字段在 Redis 不可用时为 null。
 */
public final class OverviewDtos {

    private OverviewDtos() {
    }

    /**
     * 当前租户的总览。
     *
     * @param productCount  产品数
     * @param propertyCount 所有产品的物模型属性总数
     * @param deviceCount   设备数
     * @param enabledCount  启用的设备数
     * @param disabledCount 禁用的设备数
     * @param onlineCount   在线设备数；Redis 不可用时为 null
     * @param deadLastHour  最近 1 小时的死信统计；Redis 不可用时为 null
     * @param recentDevices 最后上报时间最近的设备（最多 5 台，新的在前）；Redis 不可用时为空列表
     * @param products      每个产品的设备数与在线数，按设备数降序
     */
    public record OverviewView(int productCount, int propertyCount, int deviceCount, int enabledCount,
                               int disabledCount, Integer onlineCount, DeadSummary deadLastHour,
                               List<RecentDevice> recentDevices, List<ProductStat> products) {
    }

    /**
     * 死信统计。
     *
     * @param total    条数
     * @param byReason 按死信原因（DeadReason 名称）分组的条数，按原因名称排序
     */
    public record DeadSummary(int total, Map<String, Integer> byReason) {
    }

    /**
     * 最近上报的设备。
     *
     * @param lastReportAt 最后上报时间（毫秒）
     */
    public record RecentDevice(String deviceKey, String name, String productKey, String productName, long lastReportAt) {
    }

    /**
     * 单个产品的设备统计。
     *
     * @param onlineCount 在线设备数；Redis 不可用时为 null
     */
    public record ProductStat(String productKey, String name, int deviceCount, Integer onlineCount) {
    }
}
