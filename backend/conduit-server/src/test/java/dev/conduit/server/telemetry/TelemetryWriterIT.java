package dev.conduit.server.telemetry;

import com.influxdb.client.InfluxDBClient;
import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static dev.conduit.server.support.InfluxTestSupport.count;
import static org.assertj.core.api.Assertions.assertThat;

class TelemetryWriterIT extends IntegrationTestBase {

    @Autowired
    TelemetryWriter writer;

    @Autowired
    InfluxDBClient influx;

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
        assertThat(count(influx, dk, "Ua")).isEqualTo(1);
        assertThat(count(influx, dk, "mode")).isEqualTo(1);
        assertThat(count(influx, dk, "alarm")).isEqualTo(1);
    }

    @Test
    void rewritingSameTimestampIsIdempotent() {
        String dk = unique("dk");

        writer.write(List.of(point(dk, now, Map.of("Ua", 220.0))));
        writer.write(List.of(point(dk, now, Map.of("Ua", 220.0))));

        assertThat(count(influx, dk, "Ua")).isEqualTo(1);
    }

    @Test
    void typeConflictRejectsOnlyOffendingPoint() {
        String dk = unique("dk");
        writer.write(List.of(point(dk, now - 2000, Map.of("Ua", 220.0))));

        WriteResult result = writer.write(List.of(
                point(dk, now - 1000, Map.of("Ua", "text")),
                point(dk, now, Map.of("Ib", 2.0))));

        assertThat(result.rejected()).extracting(WriteResult.Rejected::index).containsExactly(0);
        assertThat(count(influx, dk, "Ib")).isEqualTo(1);
        assertThat(count(influx, dk, "Ua")).isEqualTo(1);
    }

    @Test
    void emptyBatchIsNoop() {
        assertThat(writer.write(List.of()).rejected()).isEmpty();
    }
}
