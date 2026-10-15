package dev.conduit.server.telemetry;

import java.time.Duration;
import java.util.List;

/**
 * 历史查询的时间桶策略（V0.2-B spec 2.2）：每条曲线最多 1000 个点，聚合时从固定档位中取能满足上限的最小桶。
 * 档位固定，相邻请求（如自动刷新）的桶边界一致，曲线不会跳动。
 */
public final class BucketPolicy {

    /** 每个测点的点数上限：原始点数都不超过它时走原始模式，否则聚合后的桶数不超过它。 */
    public static final int MAX_POINTS = 1000;

    /** 桶档位：1 秒、5 秒、10 秒、30 秒、1 分钟、5 分钟、15 分钟、30 分钟、1 小时、3 小时、6 小时、12 小时。 */
    public static final List<Duration> LADDER = List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30),
            Duration.ofHours(1), Duration.ofHours(3), Duration.ofHours(6), Duration.ofHours(12));

    private BucketPolicy() {
    }

    /**
     * 选择聚合时间桶。
     *
     * @param spanMs 查询跨度 to − from（毫秒）
     * @return 满足 spanMs ≤ 桶 × 1000 的最小档位；跨度超出最大档能覆盖的范围时返回 12 小时
     */
    public static Duration bucketFor(long spanMs) {
        for (Duration bucket : LADDER) {
            if (spanMs <= bucket.toMillis() * MAX_POINTS) {
                return bucket;
            }
        }
        return LADDER.getLast();
    }
}
