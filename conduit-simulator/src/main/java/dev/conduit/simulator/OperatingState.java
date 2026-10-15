package dev.conduit.simulator;

/**
 * 演示电表的运行状态（枚举属性 st「运行状态」：0 停机、1 运行、2 检修），用于在历史页演示状态阶梯线。
 * 按 deviceKey 与整点小时序号确定性生成：同一小时内不变，到整点时可能切换；约 80% 的（设备, 小时）为运行，
 * 停机与检修各约 10%。重启模拟器不会改变已过去时段的状态。
 */
public final class OperatingState {

    public static final String IDENTIFIER = "st";
    public static final long STOPPED = 0;
    public static final long RUNNING = 1;
    public static final long MAINTENANCE = 2;

    private static final long HOUR_MS = 3_600_000L;

    private OperatingState() {
    }

    /**
     * 设备在某一时刻所在小时的状态。
     *
     * @param deviceKey   设备 Key（不同设备的状态序列互不相同）
     * @param epochMillis 上报时间（毫秒）
     * @return {@link #STOPPED}、{@link #RUNNING} 或 {@link #MAINTENANCE}
     */
    public static long at(String deviceKey, long epochMillis) {
        long hour = Math.floorDiv(epochMillis, HOUR_MS);
        long bucket = Math.floorMod(mix(deviceKey.hashCode() * 0x9E3779B97F4A7C15L ^ hour), 10L);
        if (bucket < 8) {
            return RUNNING;
        }
        return bucket == 8 ? STOPPED : MAINTENANCE;
    }

    /** SplitMix64 的末端混合：把相邻的输入（相邻小时、相近的哈希值）打散成均匀分布。 */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
