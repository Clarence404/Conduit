package dev.conduit.server.pipeline.codec;

import com.fasterxml.jackson.databind.JsonNode;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * 所有解码器共用的时间戳规则：毫秒整数，范围 [now − 30 天, now + 5 分钟]，缺省取接收时间。
 * 超出窗口的数据不写入时序库，按 TS_OUT_OF_RANGE 进死信。
 */
@Component
public class TimestampPolicy {

    static final Duration MAX_PAST = Duration.ofDays(30);
    static final Duration MAX_FUTURE = Duration.ofMinutes(5);

    private final Clock clock;

    public TimestampPolicy(Clock clock) {
        this.clock = clock;
    }

    /**
     * 解析报文中的 ts 节点。
     *
     * @param ts         报文中的 ts 节点；缺失或 null 时直接返回 receivedAt（不再做窗口检查）
     * @param receivedAt 接收时间（毫秒）
     * @throws DecodeException TS_OUT_OF_RANGE：ts 不是可放入 long 的整数，或超出允许窗口
     */
    public long resolve(JsonNode ts, long receivedAt) {
        if (ts == null || ts.isNull()) {
            return receivedAt;
        }
        if (!ts.isIntegralNumber() || !ts.canConvertToLong()) {
            throw new DecodeException(DeadReason.TS_OUT_OF_RANGE, "ts must be integer milliseconds");
        }
        return check(ts.longValue());
    }

    /**
     * 校验毫秒时间戳落在 [now − 30 天, now + 5 分钟] 内（now 取注入的 Clock）。
     *
     * @return 原样返回 ts
     * @throws DecodeException TS_OUT_OF_RANGE：超出窗口
     */
    public long check(long ts) {
        long now = clock.millis();
        if (ts < now - MAX_PAST.toMillis() || ts > now + MAX_FUTURE.toMillis()) {
            throw new DecodeException(DeadReason.TS_OUT_OF_RANGE, "ts " + ts + " outside allowed window");
        }
        return ts;
    }
}
