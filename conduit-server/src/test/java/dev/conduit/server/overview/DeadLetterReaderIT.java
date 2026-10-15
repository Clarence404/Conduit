package dev.conduit.server.overview;

import dev.conduit.common.bus.MessageBusProperties;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DeadLetterReader 集成测试（真实 Redis）：按消息 ID 时间截止、最新在前、条数上限、流不存在时为空。 */
class DeadLetterReaderIT extends IntegrationTestBase {

    /** 用显式 ID 写一条死信，使测试能构造 1 小时以前的条目（独立的流 key，ID 递增写入）。 */
    private void add(String key, long millis, String reason) {
        redis.opsForStream().add(StreamRecords.newRecord().in(key).withId(RecordId.of(millis + "-0"))
                .ofMap(Map.of("pk", "pk-" + reason, "reason", reason)));
    }

    @Test
    void readsNewestFirstBackToTheCutoffWithinTheLimit() {
        String key = unique("test:dead:");
        long now = System.currentTimeMillis();
        add(key, now - 7_200_000, "old");
        add(key, now - 60_000, "a");
        add(key, now - 30_000, "b");
        add(key, now - 10_000, "c");
        DeadLetterReader reader = new DeadLetterReader(redis, new MessageBusProperties("test:up", key, 1000, 1000));

        List<Map<String, String>> all = reader.since(now - 3_600_000, 10);
        assertThat(all).extracting((Map<String, String> r) -> r.get("reason")).containsExactly("c", "b", "a");
        assertThat(all.getFirst()).containsEntry("pk", "pk-c");
        assertThat(reader.since(now - 3_600_000, 2)).extracting((Map<String, String> r) -> r.get("reason"))
                .containsExactly("c", "b");
        redis.delete(key);
    }

    @Test
    void missingStreamGivesAnEmptyList() {
        DeadLetterReader reader = new DeadLetterReader(redis,
                new MessageBusProperties("test:up", unique("test:none:"), 1000, 1000));

        assertThat(reader.since(System.currentTimeMillis() - 3_600_000, 10)).isEmpty();
    }
}
