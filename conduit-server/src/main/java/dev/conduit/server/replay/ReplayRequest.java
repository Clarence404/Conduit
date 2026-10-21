package dev.conduit.server.replay;

import dev.conduit.server.web.ApiException;

/**
 * 重放请求：毫秒时间范围，左闭右开（from 含、to 不含），与设备列表的时间筛选约定一致。
 * 跨度上限用于防止把 from 误写成 0 而重灌整条流。
 */
public record ReplayRequest(Long from, Long to) {

    /** 允许的最大毫秒值（9999-12-31T23:59:59.999Z），与设备列表时间筛选相同。 */
    public static final long MAX_MILLIS = 253402300799999L;

    /** 单次重放允许的最大跨度：6 小时。 */
    public static final long MAX_SPAN_MILLIS = 6L * 60 * 60 * 1000;

    /**
     * 校验取值并返回自身，便于链式调用。
     *
     * @throws ApiException 400：from / to 缺失、超出 0～{@link #MAX_MILLIS}、from 不小于 to，或跨度超过 6 小时
     */
    public ReplayRequest validated() {
        if (from == null) {
            throw ApiException.badRequest("from 必填");
        }
        if (to == null) {
            throw ApiException.badRequest("to 必填");
        }
        checkBound("from", from);
        checkBound("to", to);
        if (from >= to) {
            throw ApiException.badRequest("from 必须小于 to");
        }
        if (to - from > MAX_SPAN_MILLIS) {
            throw ApiException.badRequest("时间跨度不能超过 6 小时");
        }
        return this;
    }

    /** 单个毫秒参数必须落在 0～{@link #MAX_MILLIS} 内。 */
    private static void checkBound(String name, long value) {
        if (value < 0 || value > MAX_MILLIS) {
            throw ApiException.badRequest(name + " 超出范围（0 到 " + MAX_MILLIS + "）");
        }
    }
}
