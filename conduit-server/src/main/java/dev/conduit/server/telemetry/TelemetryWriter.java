package dev.conduit.server.telemetry;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TelemetryWriter {

    static final String MEASUREMENT = "property";

    private final InfluxClient client;
    private final InfluxProperties props;

    public TelemetryWriter(InfluxClient client, InfluxProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * 一次请求写入整批。无法转换的点（不支持的类型、null 等）在写入前拒绝；InfluxDB 逐行拒绝的点（通常是字段类型冲突）
     * 按 line_number 映射回原始下标；其余行已写入。下游不可用抛 TelemetryUnavailableException（可原地重试）；
     * 非逐行拒绝的 400 说明请求本身有问题，抛 IllegalStateException（不可原地重试，见实施计划 Task 1 的决定）。
     */
    public WriteResult write(List<TelemetryPoint> points) {
        if (points.isEmpty()) {
            return WriteResult.ok();
        }
        List<WriteResult.Rejected> rejected = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            try {
                lines.add(toLine(points.get(i)));
                indexes.add(i);
            } catch (RuntimeException e) {
                rejected.add(new WriteResult.Rejected(i, String.valueOf(e.getMessage())));
            }
        }
        if (lines.isEmpty()) {
            return new WriteResult(rejected);
        }
        for (InfluxClient.LineError error : send(lines)) {
            int line = error.lineNumber() - 1;
            if (line < 0 || line >= indexes.size()) {
                throw new IllegalStateException("InfluxDB reported rejected line " + error.lineNumber()
                        + " outside the batch of " + lines.size());
            }
            rejected.add(new WriteResult.Rejected(indexes.get(line), error.message()));
        }
        rejected.sort(Comparator.comparingInt(WriteResult.Rejected::index));
        return new WriteResult(rejected);
    }

    private List<InfluxClient.LineError> send(List<String> lines) {
        try {
            return client.writeLines(props.database(), lines);
        } catch (InfluxHttpException e) {
            if (e.status() == 400) {
                throw new IllegalStateException("InfluxDB rejected the write request itself: " + e.getMessage(), e);
            }
            throw new TelemetryUnavailableException(e);
        } catch (RuntimeException e) {
            throw new TelemetryUnavailableException(e);
        }
    }

    static String toLine(TelemetryPoint p) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("tenant_id", Long.toString(p.tenantId()));
        tags.put("product_key", p.productKey());
        tags.put("device_key", p.deviceKey());
        return LineProtocol.line(MEASUREMENT, tags, p.fields(), p.ts());
    }
}
