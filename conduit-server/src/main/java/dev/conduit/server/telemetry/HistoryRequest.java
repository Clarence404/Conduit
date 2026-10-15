package dev.conduit.server.telemetry;

import dev.conduit.server.web.ApiException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 历史接口已校验的参数（V0.2-B spec 2.1）：去重后的测点（1～8 个，保持请求顺序）与半开时间区间 [from, to)。
 *
 * @param properties 测点标识，顺序即返回曲线的顺序
 * @param from       起点（毫秒，含）
 * @param to         终点（毫秒，不含）
 */
public record HistoryRequest(List<String> properties, long from, long to) {

    /** 单次最多查询的测点个数（去重后）。 */
    public static final int MAX_PROPERTIES = 8;
    /** 最小时间跨度：1 分钟。 */
    public static final long MIN_SPAN_MS = 60_000L;
    /** 最大时间跨度：30 天（与时序库保留期一致）。 */
    public static final long MAX_SPAN_MS = 30L * 24 * 3_600_000L;
    /** 未给时间范围时的默认跨度：1 小时。 */
    public static final long DEFAULT_SPAN_MS = 3_600_000L;
    /** 表 property 的时间列与标签列，不是测点。 */
    static final Set<String> RESERVED = Set.of("time", "tenant_id", "product_key", "device_key");

    /**
     * 解析并校验请求参数。
     *
     * @param properties 逗号分隔的测点标识（各项去掉首尾空白后校验）
     * @param from       起点毫秒，可为 null
     * @param to         终点毫秒，可为 null；from、to 都为 null 时取最近 1 小时
     * @param nowMs      当前时间（毫秒），用于默认范围
     * @return 校验后的请求
     * @throws ApiException 400：测点为空、超过 8 个、标识不合法或为保留列名；只给了一个时间；from ≥ to；
     *                      跨度不在 1 分钟～30 天
     */
    public static HistoryRequest parse(String properties, Long from, Long to, long nowMs) {
        List<String> ids = parseProperties(properties);
        if ((from == null) != (to == null)) {
            throw ApiException.badRequest("from 与 to 须同时提供");
        }
        long start = from == null ? nowMs - DEFAULT_SPAN_MS : from;
        long end = to == null ? nowMs : to;
        if (start >= end) {
            throw ApiException.badRequest("from 须小于 to");
        }
        long span = end - start;
        if (span < MIN_SPAN_MS || span > MAX_SPAN_MS) {
            throw ApiException.badRequest("时间跨度须在 1 分钟到 30 天之间");
        }
        return new HistoryRequest(ids, start, end);
    }

    /** 拆分、去空白、去重（保留首次出现的位置）并校验每个标识与个数。 */
    private static List<String> parseProperties(String properties) {
        if (properties == null || properties.isBlank()) {
            throw ApiException.badRequest("properties 不能为空");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String token : properties.split(",", -1)) {
            String id = token.trim();
            if (!HistoryQueryBuilder.IDENTIFIER.matcher(id).matches() || RESERVED.contains(id)) {
                throw ApiException.badRequest("测点标识不合法: " + id);
            }
            ids.add(id);
        }
        if (ids.size() > MAX_PROPERTIES) {
            throw ApiException.badRequest("最多查询 " + MAX_PROPERTIES + " 个测点");
        }
        return List.copyOf(ids);
    }
}
