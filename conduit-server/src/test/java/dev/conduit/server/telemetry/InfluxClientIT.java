package dev.conduit.server.telemetry;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.conduit.server.support.InfluxTestSupport.DATABASE;
import static dev.conduit.server.support.InfluxTestSupport.awaitCount;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InfluxClient 对真实 InfluxDB 3.12.0 的集成测试，同时固定 V0.2-B 依赖的 SQL 行为（参数化查询、时间参数与返回格式、
 * COUNT、date_bin + selector_last、information_schema 类型、未知列/未知库的状态码）。
 */
class InfluxClientIT extends IntegrationTestBase {

    @Autowired
    InfluxClient client;

    @Autowired
    InfluxProperties props;

    @Autowired
    InfluxSchema schema;

    final long now = System.currentTimeMillis();

    @Test
    void databaseCreationIsIdempotent() {
        String db = unique("it_db_");
        client.createDatabaseIfMissing(db, "30d");
        client.createDatabaseIfMissing(db, "30d");
    }

    @Test
    void writesAndQueriesTypedValues() {
        String dk = unique("dk");
        String f = unique("f_");
        String n = unique("n_");

        List<InfluxClient.LineError> errors = client.writeLines(DATABASE, List.of(
                "property,tenant_id=1,product_key=pk,device_key=" + dk + " " + f + "=220.0," + n + "=5i " + now));

        assertThat(errors).isEmpty();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Map<String, Object>> rows = client.querySql(DATABASE,
                    "SELECT \"" + f + "\", \"" + n + "\" FROM property WHERE device_key = '" + dk + "'");
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(((Number) row.get(f)).doubleValue()).isEqualTo(220.0);
                assertThat(((Number) row.get(n)).longValue()).isEqualTo(5L);
            });
        });
    }

    @Test
    void partialWriteReportsOneBasedLineNumbersAndKeepsGoodLines() {
        String dk = unique("dk");
        String f = unique("f_");
        client.writeLines(DATABASE, List.of("property,device_key=" + dk + " " + f + "=1.0 " + (now - 10)));

        List<InfluxClient.LineError> errors = client.writeLines(DATABASE, List.of(
                "property,device_key=" + dk + " " + f + "=2.0 " + (now - 3),
                "property,device_key=" + dk + " " + f + "=\"text\" " + (now - 2),
                "property,device_key=" + dk + " " + f + "=3.0 " + (now - 1)));

        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.lineNumber()).isEqualTo(2);
            assertThat(e.message()).startsWith("invalid column type");
        });
        awaitCount(client, dk, f, 3);
    }

    @Test
    void emptyWriteIsAPlain400NotAPartialWrite() {
        assertThatThrownBy(() -> client.writeLines(DATABASE, List.of("")))
                .isInstanceOf(InfluxHttpException.class)
                .satisfies(e -> assertThat(((InfluxHttpException) e).status()).isEqualTo(400));
    }

    @Test
    void wrongTokenIs401() {
        try (InfluxClient wrong = new InfluxClient(URI.create(props.url()), "apiv3_wrong-token")) {
            assertThatThrownBy(() -> wrong.writeLines(DATABASE, List.of("property,device_key=x a=1.0 " + now)))
                    .isInstanceOf(InfluxHttpException.class)
                    .satisfies(e -> assertThat(((InfluxHttpException) e).status()).isEqualTo(401));
        }
    }

    @Test
    void emptyQueryResultIsEmptyList() {
        assertThat(client.querySql(DATABASE,
                "SELECT data_type FROM information_schema.columns WHERE column_name = 'no-such-column'")).isEmpty();
    }

    // ---- V0.2-B：参数化查询与历史查询依赖的行为 ----

    /** 一行 property 数据：tenant_id、product_key=pk-facts、device_key 三个标签，时间为毫秒。 */
    private static String line(String tenant, String deviceKey, String fields, long ts) {
        return "property,tenant_id=" + tenant + ",product_key=pk-facts,device_key=" + deviceKey + " " + fields + " " + ts;
    }

    @Test
    void parameterizedQueryFiltersByStringAndIsoTimeParams() {
        String dk = unique("dk");
        String f = unique("f_");
        long t0 = Math.floorDiv(now - 600_000, 1_000) * 1_000;
        assertThat(client.writeLines(DATABASE, List.of(
                line("7", dk, f + "=1.0", t0),
                line("7", dk, f + "=2.0", t0 + 1_000),
                line("7", dk, f + "=3.0", t0 + 2_000),
                line("8", dk, f + "=9.0", t0 + 500),
                line("7", unique("dk"), f + "=9.0", t0 + 600)))).isEmpty();
        awaitCount(client, dk, f, 4);

        List<Map<String, Object>> rows = client.querySql(DATABASE,
                "SELECT time, \"" + f + "\" AS \"v_0\" FROM property WHERE tenant_id = $tenant AND device_key = $device"
                        + " AND time >= $from AND time < $to ORDER BY time",
                Map.of("tenant", "7", "device", dk, "from", InfluxTime.iso(t0), "to", InfluxTime.iso(t0 + 2_000)));

        assertThat(rows).hasSize(2);
        String time = (String) rows.getFirst().get("time");
        assertThat(time).doesNotEndWith("Z").doesNotContain("+");
        assertThat(InfluxTime.epochMillis(time)).isEqualTo(t0);
        assertThat(((Number) rows.get(0).get("v_0")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) rows.get(1).get("v_0")).doubleValue()).isEqualTo(2.0);
    }

    @Test
    void countIsPerColumnNonNullAndRawRowsOmitOrNullMissingValues() {
        String dk = unique("dk");
        String f = unique("f_");
        String sp = unique("sp_");
        long t0 = Math.floorDiv(now - 600_000, 1_000) * 1_000;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String fields = f + "=" + i + ".0" + (i == 5 || i == 6 ? "," + sp + "=1.0" : "");
            lines.add(line("7", dk, fields, t0 + i * 1_000L));
        }
        assertThat(client.writeLines(DATABASE, lines)).isEmpty();
        awaitCount(client, dk, f, 10);

        List<Map<String, Object>> counts = client.querySql(DATABASE,
                "SELECT COUNT(\"" + f + "\") AS \"n_0\", COUNT(\"" + sp + "\") AS \"n_1\" FROM property"
                        + " WHERE device_key = $device", Map.of("device", dk));
        assertThat(counts).singleElement().satisfies(row -> {
            assertThat(((Number) row.get("n_0")).longValue()).isEqualTo(10);
            assertThat(((Number) row.get("n_1")).longValue()).isEqualTo(2);
        });

        List<Map<String, Object>> raw = client.querySql(DATABASE,
                "SELECT time, \"" + sp + "\" AS \"v_0\" FROM property WHERE device_key = $device ORDER BY time",
                Map.of("device", dk));
        assertThat(raw).hasSize(10);
        assertThat(raw).filteredOn(row -> row.get("v_0") == null).hasSize(8);
    }

    @Test
    void dateBinAggregatesAndSelectorLastWorkForIntAndBoolAlignedTo1970() {
        String dk = unique("dk");
        String f = unique("f_");
        String st = unique("st");
        String on = unique("on");
        long t0 = Math.floorDiv(now - 600_000, 5_000) * 5_000;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            lines.add(line("7", dk, f + "=" + i + ".0," + st + "=" + (i % 3) + "i," + on + "=" + (i % 2 == 0),
                    t0 + i * 1_000L));
        }
        assertThat(client.writeLines(DATABASE, lines)).isEmpty();
        awaitCount(client, dk, f, 10);

        List<Map<String, Object>> rows = client.querySql(DATABASE,
                "SELECT date_bin(INTERVAL '5 seconds', time) AS t, avg(\"" + f + "\") AS \"avg_0\", min(\"" + f
                        + "\") AS \"min_0\", max(\"" + f + "\") AS \"max_0\", selector_last(\"" + st
                        + "\", time)['value'] AS \"last_1\", selector_last(\"" + on + "\", time)['value'] AS \"last_2\""
                        + " FROM property WHERE device_key = $device AND time >= $from AND time < $to"
                        + " GROUP BY 1 ORDER BY 1",
                Map.of("device", dk, "from", InfluxTime.iso(t0 + 3_000), "to", InfluxTime.iso(t0 + 10_000)));

        assertThat(rows).hasSize(2);
        Map<String, Object> first = rows.get(0);
        assertThat(InfluxTime.epochMillis(first.get("t"))).as("first bucket starts before from").isEqualTo(t0);
        assertThat(((Number) first.get("avg_0")).doubleValue()).isEqualTo(3.5);
        assertThat(((Number) first.get("min_0")).doubleValue()).isEqualTo(3.0);
        assertThat(((Number) first.get("max_0")).doubleValue()).isEqualTo(4.0);
        assertThat(((Number) first.get("last_1")).longValue()).isEqualTo(1L);
        assertThat(first.get("last_2")).isEqualTo(true);
        Map<String, Object> second = rows.get(1);
        assertThat(InfluxTime.epochMillis(second.get("t"))).isEqualTo(t0 + 5_000);
        assertThat(((Number) second.get("avg_0")).doubleValue()).isEqualTo(7.0);
        assertThat(((Number) second.get("last_1")).longValue()).isEqualTo(0L);
        assertThat(second.get("last_2")).isEqualTo(false);
    }

    @Test
    void informationSchemaReportsArrowTypes() {
        String dk = unique("dk");
        String f = unique("f_");
        String n = unique("n_");
        String b = unique("b_");
        String s = unique("s_");
        assertThat(client.writeLines(DATABASE, List.of(
                line("7", dk, f + "=1.0," + n + "=1i," + b + "=true," + s + "=\"x\"", now - 1_000)))).isEmpty();
        awaitCount(client, dk, f, 1);

        Map<String, String> types = new HashMap<>();
        client.querySql(DATABASE, InfluxSchema.COLUMNS_SQL)
                .forEach(row -> types.put((String) row.get("column_name"), (String) row.get("data_type")));

        assertThat(types).containsEntry(f, "Float64").containsEntry(n, "Int64")
                .containsEntry(b, "Boolean").containsEntry(s, "Utf8");
        assertThat(types.get("tenant_id")).startsWith("Dictionary(");
        assertThat(types.get("time")).startsWith("Timestamp");
    }

    @Test
    void unknownColumnIsAServerError() {
        // 表必须已存在：表尚未创建时 InfluxDB 返回 400（table 'public.iox.property' not found），不是 500
        String dk = unique("dk");
        String f = unique("f_");
        assertThat(client.writeLines(DATABASE, List.of(line("7", dk, f + "=1.0", now - 1_000)))).isEmpty();
        awaitCount(client, dk, f, 1);

        assertThatThrownBy(() -> client.querySql(DATABASE,
                "SELECT COUNT(\"" + unique("Zz") + "\") AS n FROM property WHERE device_key = $device",
                Map.of("device", "x")))
                .isInstanceOf(InfluxHttpException.class)
                .satisfies(e -> assertThat(((InfluxHttpException) e).status()).isEqualTo(500));
    }

    @Test
    void unknownDatabaseIs404() {
        assertThatThrownBy(() -> client.querySql(unique("no_db_"),
                "SELECT time FROM property WHERE device_key = $device", Map.of("device", "x")))
                .isInstanceOf(InfluxHttpException.class)
                .satisfies(e -> assertThat(((InfluxHttpException) e).status()).isEqualTo(404));
    }

    @Test
    void parameterizedEmptyResultIsEmptyList() {
        assertThat(client.querySql(DATABASE, "SELECT time FROM property WHERE device_key = $device",
                Map.of("device", unique("nobody")))).isEmpty();
    }

    @Test
    void schemaSeesAColumnWrittenAfterTheLastLoad() {
        String dk = unique("dk");
        String f = unique("f_");
        assertThat(schema.lookup(List.of(f))).isEmpty();

        assertThat(client.writeLines(DATABASE, List.of(line("7", dk, f + "=1.0", now - 1_000)))).isEmpty();
        awaitCount(client, dk, f, 1);

        assertThat(schema.lookup(List.of(f))).containsEntry(f, InfluxSchema.ColumnType.FLOAT);
    }
}
