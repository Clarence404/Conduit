package dev.conduit.server.support;

import dev.conduit.server.telemetry.InfluxClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 写入后再查询的测试只用 now 附近的时间戳：早于保留期（30 天）的点会被接受但查询不到。 */
public final class InfluxTestSupport {

    public static final String DATABASE = "telemetry";

    private InfluxTestSupport() {
    }

    /** 某设备某字段的点数。字段列还不存在时 InfluxDB 会对 COUNT 报 500，这里先查 information_schema 返回 0。 */
    public static long count(InfluxClient client, String deviceKey, String field) {
        if (fieldType(client, field) == null) {
            return 0;
        }
        List<Map<String, Object>> rows = client.querySql(DATABASE,
                "SELECT COUNT(" + identifier(field) + ") AS n FROM property WHERE device_key = " + literal(deviceKey));
        return ((Number) rows.getFirst().get("n")).longValue();
    }

    /** 正向计数断言：短暂等待，避免 WAL 刷新造成的偶发失败。断言为 0 时直接用 count。 */
    public static void awaitCount(InfluxClient client, String deviceKey, String field, long expected) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(count(client, deviceKey, field))
                        .as("points of %s.%s", deviceKey, field).isEqualTo(expected));
    }

    /** 列的 Arrow 类型（Float64 / Int64 / Boolean / Utf8），列不存在时返回 null。 */
    public static String fieldType(InfluxClient client, String field) {
        List<Map<String, Object>> rows = client.querySql(DATABASE,
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'property' AND column_name = "
                        + literal(field));
        return rows.isEmpty() ? null : (String) rows.getFirst().get("data_type");
    }

    static String identifier(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
