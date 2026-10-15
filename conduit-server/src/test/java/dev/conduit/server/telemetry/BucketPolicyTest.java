package dev.conduit.server.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** BucketPolicy 的单元测试：各档位边界（spec 7：30 天 → 1 小时）。 */
class BucketPolicyTest {

    @Test
    void ladderIsTheSpecLadder() {
        assertThat(BucketPolicy.LADDER).extracting(Duration::toSeconds)
                .containsExactly(1L, 5L, 10L, 30L, 60L, 300L, 900L, 1800L, 3600L, 10800L, 21600L, 43200L);
        assertThat(BucketPolicy.MAX_POINTS).isEqualTo(1000);
    }

    @ParameterizedTest(name = "span {0} ms -> {1} s")
    @CsvSource({
            "60000, 1",            // 1 分钟
            "1000000, 1",          // 正好 1000 个 1 秒桶
            "1000001, 5",
            "3600000, 5",          // 1 小时
            "5000000, 5",
            "5000001, 10",
            "10000001, 30",
            "21600000, 30",        // 6 小时
            "30000001, 60",
            "86400000, 300",       // 24 小时 → 5 分钟（1440 个 1 分钟桶超过 1000）
            "604800000, 900",      // 7 天 → 15 分钟
            "2592000000, 3600",    // 30 天 → 1 小时
            "43200000000, 43200",  // 正好 1000 个 12 小时桶
            "43200000001, 43200"   // 超出最大档时仍取 12 小时
    })
    void picksTheSmallestBucketWithAtMostMaxPoints(long spanMs, long expectedSeconds) {
        assertThat(BucketPolicy.bucketFor(spanMs)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }
}
