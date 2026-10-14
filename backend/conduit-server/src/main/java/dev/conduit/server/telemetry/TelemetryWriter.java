package dev.conduit.server.telemetry;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.exceptions.InfluxException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TelemetryWriter {

    static final String MEASUREMENT = "property";

    private final InfluxDBClient client;
    private final InfluxProperties props;

    public TelemetryWriter(InfluxDBClient client, InfluxProperties props) {
        this.client = client;
        this.props = props;
    }

    public WriteResult write(List<TelemetryPoint> points) {
        if (points.isEmpty()) {
            return WriteResult.ok();
        }
        WriteApiBlocking api = client.getWriteApiBlocking();
        try {
            api.writePoints(props.bucket(), props.org(), points.stream().map(TelemetryWriter::toPoint).toList());
            return WriteResult.ok();
        } catch (InfluxException e) {
            if (!isDataRejection(e)) {
                throw new TelemetryUnavailableException(e);
            }
        } catch (RuntimeException e) {
            throw new TelemetryUnavailableException(e);
        }

        // 整批被拒（通常是字段类型冲突）：逐条重写，找出真正有问题的点。重写成功的点是幂等的。
        List<WriteResult.Rejected> rejected = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            try {
                api.writePoint(props.bucket(), props.org(), toPoint(points.get(i)));
            } catch (InfluxException e) {
                if (!isDataRejection(e)) {
                    throw new TelemetryUnavailableException(e);
                }
                rejected.add(new WriteResult.Rejected(i, e.getMessage()));
            } catch (RuntimeException e) {
                throw new TelemetryUnavailableException(e);
            }
        }
        return new WriteResult(rejected);
    }

    /** 只有 400/422 视为数据问题；401/403/404 是配置问题，按不可用处理以免整批进死信。 */
    private static boolean isDataRejection(InfluxException e) {
        return e.status() == 400 || e.status() == 422;
    }

    static Point toPoint(TelemetryPoint p) {
        Point point = Point.measurement(MEASUREMENT)
                .addTag("tenant_id", Long.toString(p.tenantId()))
                .addTag("product_key", p.productKey())
                .addTag("device_key", p.deviceKey())
                .time(p.ts(), WritePrecision.MS);
        p.fields().forEach((name, value) -> {
            switch (value) {
                case Number n -> point.addField(name, n.doubleValue());
                case Boolean b -> point.addField(name, b);
                case String s -> point.addField(name, s);
                default -> throw new IllegalArgumentException("Unsupported field type for " + name);
            }
        });
        return point;
    }
}
