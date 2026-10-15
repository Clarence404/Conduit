package dev.conduit.server.telemetry;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * property 表的列名与列类型缓存（来自 information_schema.columns），写入后 60 秒过期。
 * 查询中出现缓存里没有的列时强制刷新一次（该列可能刚被首次写入）；刷新后仍没有，即视为从未上报。
 * 历史查询只拼接本缓存确认存在的列名：InfluxDB 对未知列返回 500（Schema error），不能把它当作「空曲线」。
 */
@Component
public class InfluxSchema {

    static final Duration TTL = Duration.ofSeconds(60);
    static final String COLUMNS_SQL =
            "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'property'";

    /** 历史查询关心的列类型，由 InfluxDB（Arrow）类型归类而来。 */
    public enum ColumnType {
        FLOAT, INTEGER, BOOLEAN, STRING, OTHER;

        /**
         * 归类 information_schema 中的 data_type。
         *
         * @param arrowType 如 Float64、Int64、Boolean、Utf8、Dictionary(Int32, Utf8)、Timestamp(ns)
         * @return Float64 → FLOAT；Int64/UInt64 → INTEGER；Boolean → BOOLEAN；Utf8/LargeUtf8/Dictionary(…)（标签列）
         *         → STRING；其余 → OTHER
         */
        static ColumnType of(String arrowType) {
            if (arrowType == null) {
                return OTHER;
            }
            return switch (arrowType) {
                case "Float64" -> FLOAT;
                case "Int64", "UInt64" -> INTEGER;
                case "Boolean" -> BOOLEAN;
                case "Utf8", "LargeUtf8" -> STRING;
                default -> arrowType.startsWith("Dictionary(") ? STRING : OTHER;
            };
        }
    }

    /** 一次加载的结果与加载时刻。 */
    private record Snapshot(Map<String, ColumnType> columns, Instant loadedAt) {
    }

    private final InfluxClient client;
    private final InfluxProperties props;
    private final Clock clock;
    private Snapshot snapshot;

    /** 构造缓存；client 与 props 指明查询哪个库，clock 用于判断过期（测试可注入假时钟）。 */
    public InfluxSchema(InfluxClient client, InfluxProperties props, Clock clock) {
        this.client = client;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 查询若干列的类型。缓存为空或已过期时重新加载；有缓存中不存在的列时再强制刷新一次（每次调用最多加载一次）。
     * InfluxDB 的异常原样抛出，由调用方映射为 503/500。
     *
     * @param identifiers 已通过标识符校验的列名
     * @return 按 identifiers 顺序的「列名 → 类型」，不存在的列不在结果中
     */
    public synchronized Map<String, ColumnType> lookup(Collection<String> identifiers) {
        boolean reloaded = false;
        if (snapshot == null || !clock.instant().isBefore(snapshot.loadedAt().plus(TTL))) {
            reload();
            reloaded = true;
        }
        if (!reloaded && !snapshot.columns().keySet().containsAll(identifiers)) {
            reload();
        }
        Map<String, ColumnType> result = new LinkedHashMap<>();
        for (String id : identifiers) {
            ColumnType type = snapshot.columns().get(id);
            if (type != null) {
                result.put(id, type);
            }
        }
        return result;
    }

    /** 从 information_schema 重新加载 property 表的全部列。 */
    private void reload() {
        List<Map<String, Object>> rows = client.querySql(props.database(), COLUMNS_SQL);
        Map<String, ColumnType> columns = new HashMap<>();
        for (Map<String, Object> row : rows) {
            columns.put(String.valueOf(row.get("column_name")), ColumnType.of((String) row.get("data_type")));
        }
        snapshot = new Snapshot(Map.copyOf(columns), clock.instant());
    }
}
