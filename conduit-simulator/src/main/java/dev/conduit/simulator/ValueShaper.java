package dev.conduit.simulator;

/** 把生成的原始数值整理为满足物模型约束的上报值（纯函数），保证不会被流水线以 FIELD_REJECTED 剔除。 */
final class ValueShaper {

    private ValueShaper() {
    }

    /**
     * 整理数值。
     *
     * @return INT：四舍五入后夹在整数上下限内的 Long；DOUBLE：夹在上下限内并按小数位（未设置为 3，最多 6）
     *         四舍五入的 Double，舍入后仍在上下限内
     */
    static Object shape(PropertyDef p, double raw) {
        double lo = p.minValue() == null ? Double.NEGATIVE_INFINITY : p.minValue();
        double hi = p.maxValue() == null ? Double.POSITIVE_INFINITY : p.maxValue();
        if ("INT".equalsIgnoreCase(p.dataType())) {
            long v = Math.round(raw);
            if (v < lo) {
                v = (long) Math.ceil(lo);
            }
            if (v > hi) {
                v = (long) Math.floor(hi);
            }
            return v;
        }
        int digits = p.decimals() == null ? 3 : Math.max(0, Math.min(p.decimals(), 6));
        double scale = Math.pow(10, digits);
        double v = Math.round(DayCurves.clamp(raw, lo, hi) * scale) / scale;
        if (v > hi) {
            v = Math.floor(hi * scale) / scale;
        }
        if (v < lo) {
            v = Math.ceil(lo * scale) / scale;
        }
        return v;
    }
}
