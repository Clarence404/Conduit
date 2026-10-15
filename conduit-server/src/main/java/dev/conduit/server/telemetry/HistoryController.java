package dev.conduit.server.telemetry;

import dev.conduit.server.device.DeviceDtos.DeviceView;
import dev.conduit.server.device.DeviceService;
import dev.conduit.server.telemetry.HistoryDtos.HistoryView;
import dev.conduit.server.tenant.TenantContext;
import dev.conduit.server.thingmodel.ProductProfile;
import dev.conduit.server.thingmodel.PropertySpec;
import dev.conduit.server.thingmodel.ThingModelCache;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;

/** 设备历史查询接口（V0.2-B spec 2）：参数校验、按当前租户查设备、取物模型，再交给 {@link HistoryService}。 */
@RestController
public class HistoryController {

    private final DeviceService devices;
    private final ThingModelCache thingModels;
    private final HistoryService history;
    private final Clock clock;

    public HistoryController(DeviceService devices, ThingModelCache thingModels, HistoryService history,
                             Clock clock) {
        this.devices = devices;
        this.thingModels = thingModels;
        this.history = history;
        this.clock = clock;
    }

    /**
     * 查询设备若干测点的历史曲线。
     *
     * @param deviceKey  设备 Key；不存在或属于其他租户 → 404
     * @param properties 逗号分隔的测点，1～8 个
     * @param from       起点毫秒（含），与 to 同时给出；都不给时为最近 1 小时
     * @param to         终点毫秒（不含）
     * @return 原始或聚合曲线
     */
    @GetMapping("/api/v1/devices/{deviceKey}/history")
    public HistoryView history(@PathVariable String deviceKey,
                               @RequestParam(required = false) String properties,
                               @RequestParam(required = false) Long from,
                               @RequestParam(required = false) Long to) {
        HistoryRequest request = HistoryRequest.parse(properties, from, to, clock.millis());
        DeviceView device = devices.get(deviceKey);
        Map<String, PropertySpec> model = thingModels.find(device.productKey())
                .map(ProductProfile::properties).orElse(Map.of());
        return history.query(TenantContext.require(), device, model, request);
    }
}
