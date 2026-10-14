package dev.conduit.server.support;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxTable;

import java.util.List;

public final class InfluxTestSupport {

    private InfluxTestSupport() {
    }

    public static long count(InfluxDBClient client, String deviceKey, String field) {
        String flux = """
                from(bucket: "telemetry")
                  |> range(start: 0)
                  |> filter(fn: (r) => r._measurement == "property" and r.device_key == "%s" and r._field == "%s")
                  |> count()
                """.formatted(deviceKey, field);
        List<FluxTable> tables = client.getQueryApi().query(flux, "conduit");
        return tables.stream()
                .flatMap(t -> t.getRecords().stream())
                .mapToLong(r -> ((Number) r.getValue()).longValue())
                .sum();
    }
}
