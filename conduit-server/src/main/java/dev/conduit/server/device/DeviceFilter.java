package dev.conduit.server.device;

import dev.conduit.server.web.ApiException;

import java.util.Locale;

/**
 * 设备列表的筛选条件，全部可选（null 表示不筛选）。{@link #parse} 负责把请求里的原始字符串参数校验并转成强类型，
 * 非法值抛 400（ApiException），因此控制器不需要类级 {@code @Validated}。
 *
 * @param productKey  限定产品
 * @param keyword     名称或 deviceKey 模糊匹配
 * @param status      ENABLED 或 DISABLED（解析时不区分大小写，统一为大写）
 * @param online      在线状态；依赖 Redis
 * @param reported    最近上报情况；依赖 Redis
 * @param createdFrom 创建时间下界（毫秒，含）
 * @param createdTo   创建时间上界（毫秒，不含）
 */
public record DeviceFilter(String productKey, String keyword, String status, Boolean online, Reported reported,
                           Long createdFrom, Long createdTo) {

    /** 最近上报分类：RECENT 为一小时内有上报，STALE 为有上报但已超过一小时，NEVER 为从未上报。 */
    public enum Reported {
        RECENT, STALE, NEVER
    }

    /** 是否需要读取 Redis（online 或 reported 任一存在），决定走内存筛选路径还是纯数据库分页路径。 */
    public boolean needsRedis() {
        return online != null || reported != null;
    }

    /**
     * 解析并校验原始请求参数。空白字符串视同未提供。
     *
     * @throws ApiException 400：status / online / reported 取值非法，createdFrom / createdTo 不是整数，
     *                      或同时给出且 createdFrom 大于等于 createdTo
     */
    public static DeviceFilter parse(String productKey, String keyword, String status, String online,
                                     String reported, String createdFrom, String createdTo) {
        String st = blankToNull(status);
        if (st != null) {
            st = st.toUpperCase(Locale.ROOT);
        }
        if (st != null && !st.equals(Device.ENABLED) && !st.equals(Device.DISABLED)) {
            throw ApiException.badRequest("status 只能是 ENABLED 或 DISABLED");
        }
        Boolean on = null;
        String onlineParam = blankToNull(online);
        if (onlineParam != null) {
            if (onlineParam.equalsIgnoreCase("true")) {
                on = true;
            } else if (onlineParam.equalsIgnoreCase("false")) {
                on = false;
            } else {
                throw ApiException.badRequest("online 只能是 true 或 false");
            }
        }
        Reported rep = null;
        String reportedParam = blankToNull(reported);
        if (reportedParam != null) {
            try {
                rep = Reported.valueOf(reportedParam.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("reported 只能是 RECENT、STALE 或 NEVER");
            }
        }
        Long from = parseMillis("createdFrom", createdFrom);
        Long to = parseMillis("createdTo", createdTo);
        if (from != null && to != null && from >= to) {
            throw ApiException.badRequest("createdFrom 必须小于 createdTo");
        }
        return new DeviceFilter(blankToNull(productKey), blankToNull(keyword), st, on, rep, from, to);
    }

    /** 创建时间参数允许的最大毫秒值（9999-12-31T23:59:59.999Z），再大 PostgreSQL 无法表示，会变成 500。 */
    static final long MAX_MILLIS = 253402300799999L;

    /** 解析毫秒时间戳参数，必须是 0 到 {@link #MAX_MILLIS} 之间的整数，否则 400。 */
    private static Long parseMillis(String name, String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        long millis;
        try {
            millis = Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " 必须是毫秒时间戳");
        }
        if (millis < 0 || millis > MAX_MILLIS) {
            throw ApiException.badRequest(name + " 超出范围（0 到 " + MAX_MILLIS + "）");
        }
        return millis;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
