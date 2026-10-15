package dev.conduit.server.telemetry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * 生成历史查询的三种 SQL（计数、原始、聚合）及其参数（V0.2-B spec 3）。纯函数。
 * 防注入：列名只接受匹配 {@link #IDENTIFIER} 的标识符并加双引号；租户、产品、设备与时间范围一律经 $参数 传入，
 * SQL 文本中不出现任何请求值。结果列使用按位置编号的别名（n_0、v_0、avg_0、last_1 …），与测点名无关。
 */
public final class HistoryQueryBuilder {

    /** 测点标识符规则，与物模型一致。 */
    public static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    private static final String WHERE = " FROM property WHERE tenant_id = $tenant AND product_key = $product"
            + " AND device_key = $device AND time >= $from AND time < $to";

    /** 一条 SQL 与它的 $参数。 */
    public record SqlQuery(String sql, Map<String, Object> params) {
    }

    /** 查询范围：租户、产品、设备与半开区间 [from, to)（毫秒）。 */
    public record Scope(long tenantId, String productKey, String deviceKey, long from, long to) {
    }

    /** 聚合查询中的一列：state 为 true 时取桶内最后值，否则取 avg/min/max。 */
    public record AggColumn(String identifier, boolean state) {
    }

    private HistoryQueryBuilder() {
    }

    /**
     * 计数 SQL：每列在范围内的非空点数，结果为一行，别名 n_i。
     *
     * @param scope   查询范围
     * @param columns 已确认存在的列，至少一个
     */
    public static SqlQuery count(Scope scope, List<String> columns) {
        requireColumns(columns);
        StringJoiner select = new StringJoiner(", ", "SELECT ", "");
        for (int i = 0; i < columns.size(); i++) {
            select.add("COUNT(" + quote(columns.get(i)) + ") AS " + quote(countAlias(i)));
        }
        return new SqlQuery(select + WHERE, params(scope));
    }

    /**
     * 原始 SQL：按时间升序返回每行的 time 与各列值（别名 v_i，行内无值时为 null 或缺省）。
     *
     * @param scope   查询范围
     * @param columns 已确认存在的列，至少一个
     */
    public static SqlQuery raw(Scope scope, List<String> columns) {
        requireColumns(columns);
        StringJoiner select = new StringJoiner(", ", "SELECT time, ", "");
        for (int i = 0; i < columns.size(); i++) {
            select.add(quote(columns.get(i)) + " AS " + quote(valueAlias(i)));
        }
        return new SqlQuery(select + WHERE + " ORDER BY time", params(scope));
    }

    /**
     * 聚合 SQL：按 date_bin（原点 1970-01-01）分桶，桶起点列为 t。数值列输出 avg_i/min_i/max_i，
     * 状态列输出 last_i（selector_last 取桶内时间最大的值）。首桶可能早于 from。
     *
     * @param scope         查询范围
     * @param columns       已确认存在的列，至少一个
     * @param bucketSeconds 桶宽（秒），来自 {@link BucketPolicy}，必须为正数
     */
    public static SqlQuery aggregate(Scope scope, List<AggColumn> columns, long bucketSeconds) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("At least one column is required");
        }
        if (bucketSeconds <= 0) {
            throw new IllegalArgumentException("Bucket must be positive: " + bucketSeconds);
        }
        StringJoiner select = new StringJoiner(", ",
                "SELECT date_bin(INTERVAL '" + bucketSeconds + " seconds', time) AS t, ", "");
        for (int i = 0; i < columns.size(); i++) {
            String column = quote(columns.get(i).identifier());
            if (columns.get(i).state()) {
                select.add("selector_last(" + column + ", time)['value'] AS " + quote(lastAlias(i)));
            } else {
                select.add("avg(" + column + ") AS " + quote(avgAlias(i)));
                select.add("min(" + column + ") AS " + quote(minAlias(i)));
                select.add("max(" + column + ") AS " + quote(maxAlias(i)));
            }
        }
        return new SqlQuery(select + WHERE + " GROUP BY 1 ORDER BY 1", params(scope));
    }

    /**
     * 校验并给标识符加双引号。
     *
     * @throws IllegalArgumentException 不匹配 {@link #IDENTIFIER}
     */
    public static String quote(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Illegal identifier: " + identifier);
        }
        return '"' + identifier + '"';
    }

    /** 第 i 个测点的计数列别名。 */
    public static String countAlias(int i) {
        return "n_" + i;
    }

    /** 第 i 个测点的原始值列别名。 */
    public static String valueAlias(int i) {
        return "v_" + i;
    }

    /** 第 i 个测点的桶内平均值列别名。 */
    public static String avgAlias(int i) {
        return "avg_" + i;
    }

    /** 第 i 个测点的桶内最小值列别名。 */
    public static String minAlias(int i) {
        return "min_" + i;
    }

    /** 第 i 个测点的桶内最大值列别名。 */
    public static String maxAlias(int i) {
        return "max_" + i;
    }

    /** 第 i 个状态测点的桶内最后值列别名。 */
    public static String lastAlias(int i) {
        return "last_" + i;
    }

    private static void requireColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("At least one column is required");
        }
    }

    /** 范围参数：tenant_id 是字符串标签，按字符串传；时间用 ISO-8601 UTC。 */
    private static Map<String, Object> params(Scope scope) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenant", Long.toString(scope.tenantId()));
        params.put("product", scope.productKey());
        params.put("device", scope.deviceKey());
        params.put("from", InfluxTime.iso(scope.from()));
        params.put("to", InfluxTime.iso(scope.to()));
        return params;
    }
}
