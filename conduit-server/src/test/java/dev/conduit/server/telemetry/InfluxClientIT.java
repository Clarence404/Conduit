package dev.conduit.server.telemetry;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static dev.conduit.server.support.InfluxTestSupport.DATABASE;
import static dev.conduit.server.support.InfluxTestSupport.awaitCount;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfluxClientIT extends IntegrationTestBase {

    @Autowired
    InfluxClient client;

    @Autowired
    InfluxProperties props;

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
}
