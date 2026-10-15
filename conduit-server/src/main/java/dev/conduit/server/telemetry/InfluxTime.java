package dev.conduit.server.telemetry;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;

/**
 * InfluxDB 3 SQL 的时间换算。查询参数用带毫秒的 ISO-8601 UTC 字符串（如 2025-10-14T00:00:00.000Z，
 * 数值参数配合 to_timestamp_millis 会静默返回空结果，不能用）；查询结果中的 time / date_bin 值是
 * 不带时区的 UTC 字符串（如 2025-10-14T00:05:00），这里统一换算为毫秒时间戳。
 */
public final class InfluxTime {

    private static final DateTimeFormatter PARAM =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private InfluxTime() {
    }

    /**
     * 毫秒时间戳 → SQL 时间参数。
     *
     * @param epochMillis 毫秒时间戳
     * @return 形如 2025-10-14T00:00:00.000Z 的字符串，可直接用于 {@code time >= $from}
     */
    public static String iso(long epochMillis) {
        return PARAM.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * 查询结果中的时间值 → 毫秒时间戳。不带时区的字符串按 UTC 解释；带 Z 或偏移量的也接受；
     * 超过毫秒精度的小数部分直接截断。
     *
     * @param influxTime 结果行中的 time 或 date_bin 列的值
     * @return 毫秒时间戳
     * @throws IllegalArgumentException 值不是可识别的时间字符串
     */
    public static long epochMillis(Object influxTime) {
        if (!(influxTime instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("Not an InfluxDB time value: " + influxTime);
        }
        try {
            TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(s, OffsetDateTime::from,
                    LocalDateTime::from);
            Instant instant = parsed instanceof OffsetDateTime odt
                    ? odt.toInstant()
                    : ((LocalDateTime) parsed).toInstant(ZoneOffset.UTC);
            return instant.toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Not an InfluxDB time value: " + influxTime, e);
        }
    }
}
