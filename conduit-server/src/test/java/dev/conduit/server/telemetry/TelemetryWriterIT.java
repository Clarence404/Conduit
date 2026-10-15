package dev.conduit.server.telemetry;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static dev.conduit.server.support.InfluxTestSupport.awaitCount;
import static dev.conduit.server.support.InfluxTestSupport.count;
import static dev.conduit.server.support.InfluxTestSupport.fieldType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TelemetryWriterIT extends IntegrationTestBase {

    @Autowired
    TelemetryWriter writer;

    @Autowired
    InfluxClient influx;

    final long now = System.currentTimeMillis();

    private TelemetryPoint point(String dk, long ts, Map<String, Object> fields) {
        return new TelemetryPoint(1, "pk", dk, ts, fields);
    }

    @Test
    void writesAllFieldTypes() {
        String dk = unique("dk");

        WriteResult result = writer.write(List.of(
                point(dk, now, Map.of("Ua", 220.0, "mode", "auto", "alarm", true))));

        assertThat(result.rejected()).isEmpty();
        awaitCount(influx, dk, "Ua", 1);
        awaitCount(influx, dk, "mode", 1);
        awaitCount(influx, dk, "alarm", 1);
    }

    @Test
    void rewritingSameTimestampIsIdempotent() {
        String dk = unique("dk");

        writer.write(List.of(point(dk, now, Map.of("Ua", 220.0))));
        writer.write(List.of(point(dk, now, Map.of("Ua", 220.0))));

        awaitCount(influx, dk, "Ua", 1);
    }

    @Test
    void typeConflictRejectsOnlyOffendingPoint() {
        String dk = unique("dk");
        writer.write(List.of(point(dk, now - 2000, Map.of("Ua", 220.0))));

        WriteResult result = writer.write(List.of(
                point(dk, now - 1000, Map.of("Ua", "text")),
                point(dk, now, Map.of("Ib", 2.0))));

        assertThat(result.rejected()).extracting(WriteResult.Rejected::index).containsExactly(0);
        awaitCount(influx, dk, "Ib", 1);
        awaitCount(influx, dk, "Ua", 1);
    }

    @Test
    void storesInfluxTypesPerJavaType() {
        String dk = unique("dk");
        String i = unique("i_");
        String f = unique("f_");
        String b = unique("b_");
        String s = unique("s_");

        WriteResult result = writer.write(List.of(point(dk, now, Map.of(i, 42L, f, 220.0, b, true, s, "auto"))));

        assertThat(result.rejected()).isEmpty();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(fieldType(influx, i)).isEqualTo("Int64");
            assertThat(fieldType(influx, f)).isEqualTo("Float64");
            assertThat(fieldType(influx, b)).isEqualTo("Boolean");
            assertThat(fieldType(influx, s)).isEqualTo("Utf8");
        });
    }

    @Test
    void emptyBatchIsNoop() {
        assertThat(writer.write(List.of()).rejected()).isEmpty();
    }
}
