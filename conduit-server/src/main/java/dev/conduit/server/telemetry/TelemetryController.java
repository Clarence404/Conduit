package dev.conduit.server.telemetry;

import dev.conduit.server.device.DeviceService;
import dev.conduit.server.thingmodel.DataType;
import dev.conduit.server.thingmodel.ProductProfile;
import dev.conduit.server.thingmodel.PropertySpec;
import dev.conduit.server.thingmodel.ThingModelCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 设备最新值的 REST 接口（给 UI 的设备详情页用）。
 * 把 Redis 中的最新值与 PostgreSQL 物模型（经缓存）合并，补上名称、单位、小数位和枚举含义。
 */
@RestController
public class TelemetryController {

    /** name/unit/decimals 在物模型中无定义时为 null；enumLabel 仅枚举且值已定义时有值。 */
    public record PointView(String identifier, String name, Object value, long ts, String unit, Integer decimals,
                            String enumLabel) {
    }

    /**
     * 设备最新值响应：设备标识及其全部测点。
     */
    public record LatestView(String deviceKey, List<PointView> values) {
    }

    private final DeviceService devices;
    private final LatestValueStore latest;
    private final ThingModelCache thingModels;

    public TelemetryController(DeviceService devices, LatestValueStore latest, ThingModelCache thingModels) {
        this.devices = devices;
        this.latest = latest;
        this.thingModels = thingModels;
    }

    /**
     * 查询设备的最新值。设备不存在（或属于其他租户）时由 DeviceService 抛 404。
     * 物模型缺失或测点未在物模型中定义时，该测点仍返回，只是 name/unit/decimals/enumLabel 为 null。
     */
    @GetMapping("/api/v1/devices/{deviceKey}/latest")
    public LatestView latest(@PathVariable String deviceKey) {
        String productKey = devices.get(deviceKey).productKey();
        Map<String, PropertySpec> model = thingModels.find(productKey)
                .map(ProductProfile::properties).orElse(Map.of());
        List<PointView> values = latest.get(deviceKey).entrySet().stream()
                .map(e -> view(e.getKey(), e.getValue(), model.get(e.getKey())))
                .toList();
        return new LatestView(deviceKey, values);
    }

    /**
     * 合并最新值与物模型定义；spec 为 null（物模型未定义该测点）时只带原始值和时间戳。
     */
    static PointView view(String identifier, LatestValue v, PropertySpec spec) {
        if (spec == null) {
            return new PointView(identifier, null, v.value(), v.ts(), null, null, null);
        }
        return new PointView(identifier, spec.name(), v.value(), v.ts(), spec.unit(), spec.decimals(),
                enumLabel(spec, v.value()));
    }

    /**
     * 枚举值的显示名称。仅当类型为 ENUM、值为整数数值且在枚举项中有定义时返回，否则返回 null。
     */
    static String enumLabel(PropertySpec spec, Object value) {
        if (spec.dataType() != DataType.ENUM || !(value instanceof Number n)) {
            return null;
        }
        double d = n.doubleValue();
        if (d != Math.rint(d)) {
            return null;
        }
        return spec.enumLabels().get(n.longValue());
    }
}
