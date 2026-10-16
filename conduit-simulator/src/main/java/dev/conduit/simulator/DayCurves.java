package dev.conduit.simulator;

/** 日变化曲线（纯函数，hour 为本地时间 0～24 的小数小时）：用电负荷、日照、用水、环境温度。 */
final class DayCurves {

    private DayCurves() {
    }

    /** 以 center 为中心、宽度 width 的高斯峰，按 24 小时环绕计算距离（23 点与 1 点相距 2 小时）。 */
    static double bump(double hour, double center, double width) {
        double d = Math.abs(hour - center);
        d = Math.min(d, 24 - d);
        return Math.exp(-(d * d) / (2 * width * width));
    }

    /** 日用电负荷系数 0.25～1：凌晨最低，上午约 10:30 与晚上 8 点两个高峰。 */
    static double loadFactor(double hour) {
        double v = 0.45 + 0.35 * bump(hour, 10.5, 2.5) + 0.45 * bump(hour, 20, 2.0) - 0.2 * bump(hour, 3.5, 2.5);
        return clamp(v, 0.25, 1.0);
    }

    /** 日照系数：6～18 点为正弦（正午为 1），其余时间恰为 0。 */
    static double sun(double hour) {
        return hour <= 6 || hour >= 18 ? 0 : Math.sin(Math.PI * (hour - 6) / 12);
    }

    /** 从 0 点到 hour 的日照系数积分（相当于满功率小时数），用于日发电量的初值。 */
    static double sunHoursUntil(double hour) {
        if (hour <= 6) {
            return 0;
        }
        if (hour >= 18) {
            return 24 / Math.PI;
        }
        return 12 / Math.PI * (1 - Math.cos(Math.PI * (hour - 6) / 12));
    }

    /** 用水流量系数 0.15～1：早 8 点、晚 7 点半两个高峰，深夜最低。 */
    static double flowFactor(double hour) {
        double v = 0.2 + 0.8 * bump(hour, 8, 1.3) + 0.65 * bump(hour, 19.5, 1.6) - 0.1 * bump(hour, 3, 2);
        return clamp(v, 0.15, 1.0);
    }

    /** 环境温度（℃）：凌晨 3 点约 11℃，下午 3 点约 25℃。 */
    static double ambientTemperature(double hour) {
        return 18 + 7 * Math.sin(2 * Math.PI * (hour - 9) / 24);
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
