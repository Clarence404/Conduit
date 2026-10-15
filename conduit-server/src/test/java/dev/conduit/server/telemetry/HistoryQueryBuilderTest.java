package dev.conduit.server.telemetry;

import dev.conduit.server.telemetry.HistoryQueryBuilder.AggColumn;
import dev.conduit.server.telemetry.HistoryQueryBuilder.Scope;
import dev.conduit.server.telemetry.HistoryQueryBuilder.SqlQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HistoryQueryBuilder 的单元测试：三种 SQL 的文本与参数、标识符校验、请求值不进入 SQL。 */
class HistoryQueryBuilderTest {

    static final String WHERE = " FROM property WHERE tenant_id = $tenant AND product_key = $product"
            + " AND device_key = $device AND time >= $from AND time < $to";
    static final Scope SCOPE = new Scope(7, "pk1", "dk1", 1760400000000L, 1760403600000L);
    static final Map<String, Object> PARAMS = Map.of("tenant", "7", "product", "pk1", "device", "dk1",
            "from", "2025-10-14T00:00:00.000Z", "to", "2025-10-14T01:00:00.000Z");

    @Test
    void countSql() {
        SqlQuery q = HistoryQueryBuilder.count(SCOPE, List.of("Ua", "st"));

        assertThat(q.sql()).isEqualTo("SELECT COUNT(\"Ua\") AS \"n_0\", COUNT(\"st\") AS \"n_1\"" + WHERE);
        assertThat(q.params()).isEqualTo(PARAMS);
    }

    @Test
    void rawSql() {
        SqlQuery q = HistoryQueryBuilder.raw(SCOPE, List.of("Ua", "on"));

        assertThat(q.sql()).isEqualTo("SELECT time, \"Ua\" AS \"v_0\", \"on\" AS \"v_1\"" + WHERE + " ORDER BY time");
        assertThat(q.params()).isEqualTo(PARAMS);
    }

    @Test
    void aggregateSql() {
        SqlQuery q = HistoryQueryBuilder.aggregate(SCOPE,
                List.of(new AggColumn("Ua", false), new AggColumn("st", true)), 300);

        assertThat(q.sql()).isEqualTo("SELECT date_bin(INTERVAL '300 seconds', time) AS t,"
                + " avg(\"Ua\") AS \"avg_0\", min(\"Ua\") AS \"min_0\", max(\"Ua\") AS \"max_0\","
                + " selector_last(\"st\", time)['value'] AS \"last_1\"" + WHERE + " GROUP BY 1 ORDER BY 1");
        assertThat(q.params()).isEqualTo(PARAMS);
    }

    @Test
    void aliasesArePositional() {
        assertThat(HistoryQueryBuilder.countAlias(2)).isEqualTo("n_2");
        assertThat(HistoryQueryBuilder.valueAlias(2)).isEqualTo("v_2");
        assertThat(HistoryQueryBuilder.avgAlias(2)).isEqualTo("avg_2");
        assertThat(HistoryQueryBuilder.minAlias(2)).isEqualTo("min_2");
        assertThat(HistoryQueryBuilder.maxAlias(2)).isEqualTo("max_2");
        assertThat(HistoryQueryBuilder.lastAlias(2)).isEqualTo("last_2");
    }

    @Test
    void requestValuesTravelOnlyAsParams() {
        Scope hostile = new Scope(7, "pk' OR '1'='1", "dk\"; DROP", 1760400000000L, 1760403600000L);

        SqlQuery q = HistoryQueryBuilder.raw(hostile, List.of("Ua"));

        assertThat(q.sql()).doesNotContain("OR '1'").doesNotContain("DROP");
        assertThat(q.params()).containsEntry("product", "pk' OR '1'='1").containsEntry("device", "dk\"; DROP");
    }

    @Test
    void acceptsSixtyFourCharacterIdentifiers() {
        String id = "a" + "b".repeat(63);
        assertThat(HistoryQueryBuilder.quote(id)).isEqualTo("\"" + id + "\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1a", "_a", "a-b", "a\"b", "Ua; DROP", "a b", "中文"})
    void rejectsIllegalIdentifiers(String id) {
        assertThatThrownBy(() -> HistoryQueryBuilder.quote(id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HistoryQueryBuilder.count(SCOPE, List.of(id)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooLongNullAndEmptyInputs() {
        assertThatThrownBy(() -> HistoryQueryBuilder.quote("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HistoryQueryBuilder.quote(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HistoryQueryBuilder.raw(SCOPE, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HistoryQueryBuilder.aggregate(SCOPE, List.of(new AggColumn("Ua", false)), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
