package dev.conduit.server.overview;

import dev.conduit.common.bus.MessageBusProperties;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 死信流读取：从最新一条往回读（XREVRANGE），直到给定时间为止，最多读 limit 条。
 * 死信流的消息 ID 毫秒部分即写入死信（failedAt）的 Redis 服务器时间，因此按 ID 截止即按时间截止。
 */
@Component
public class DeadLetterReader {

    private final StringRedisTemplate redis;
    private final String deadKey;

    public DeadLetterReader(StringRedisTemplate redis, MessageBusProperties bus) {
        this.redis = redis;
        this.deadKey = bus.deadKey();
    }

    /**
     * 读取 ID 时间不早于 sinceMillis 的死信，最新的在前。
     *
     * @param sinceMillis 截止时间（毫秒，含）
     * @param limit       最多读取的条数
     * @return 每条死信的字段（pk、dk、reason、failedAt 等）；死信流不存在时为空列表
     * @throws org.springframework.dao.DataAccessException Redis 不可用时，由调用方处理
     */
    public List<Map<String, String>> since(long sinceMillis, int limit) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().reverseRange(deadKey,
                Range.rightUnbounded(Range.Bound.inclusive(sinceMillis + "-0")), Limit.limit().count(limit));
        if (records == null) {
            return List.of();
        }
        return records.stream().map(r -> {
            Map<String, String> fields = new LinkedHashMap<>();
            r.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
            return fields;
        }).toList();
    }
}
