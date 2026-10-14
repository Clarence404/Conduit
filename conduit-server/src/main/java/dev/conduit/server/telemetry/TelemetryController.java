package dev.conduit.server.telemetry;

import dev.conduit.server.device.DeviceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TelemetryController {

    public record PointView(String name, Object value, long ts) {
    }

    public record LatestView(String deviceKey, List<PointView> values) {
    }

    private final DeviceService devices;
    private final LatestValueStore latest;

    public TelemetryController(DeviceService devices, LatestValueStore latest) {
        this.devices = devices;
        this.latest = latest;
    }

    @GetMapping("/api/v1/devices/{deviceKey}/latest")
    public LatestView latest(@PathVariable String deviceKey) {
        devices.require(deviceKey);
        List<PointView> values = latest.get(deviceKey).entrySet().stream()
                .map(e -> new PointView(e.getKey(), e.getValue().value(), e.getValue().ts()))
                .toList();
        return new LatestView(deviceKey, values);
    }
}
