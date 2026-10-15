package dev.conduit.server.pipeline.codec;

import com.fasterxml.jackson.databind.JsonNode;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/** 所有解码器共用的时间戳规则：毫秒整数，范围 [now − 30 天, now + 5 分钟]，缺省取接收时间。 */
@Component
public class TimestampPolicy {

    static final Duration MAX_PAST = Duration.ofDays(30);
    static final Duration MAX_FUTURE = Duration.ofMinutes(5);

    private final Clock clock;

    public TimestampPolicy(Clock clock) {
        this.clock = clock;
    }

    public long resolve(JsonNode ts, long receivedAt) {
        if (ts == null || ts.isNull()) {
            return receivedAt;
        }
        if (!ts.isIntegralNumber() || !ts.canConvertToLong()) {
            throw new DecodeException(DeadReason.TS_OUT_OF_RANGE, "ts must be integer milliseconds");
        }
        return check(ts.longValue());
    }

    public long check(long ts) {
        long now = clock.millis();
        if (ts < now - MAX_PAST.toMillis() || ts > now + MAX_FUTURE.toMillis()) {
            throw new DecodeException(DeadReason.TS_OUT_OF_RANGE, "ts " + ts + " outside allowed window");
        }
        return ts;
    }
}
