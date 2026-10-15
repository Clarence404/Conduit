package dev.conduit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.conduit.simulator.ConduitApiClient.DeviceSummary;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 准备演示设备：复用同名产品下已有的设备（重置密钥以拿到明文），不足的部分新建。
 * 密钥只在创建/重置时返回一次，所以每次启动都会重置已有设备的密钥。
 * 设备按 deviceKey 排序后依次命名为「N号楼配电室 电表MM」，旧名称（如 sim-001）会被改成新名称，Key 不变。
 */
public final class DemoProvisioner {

    /** 打包自 docs/examples/demo-meter.thing-model.json。 */
    public static final String DEMO_THING_MODEL = "demo-meter.thing-model.json";

    private DemoProvisioner() {
    }

    /** 同 {@link #provision(ConduitApiClient, String, int, String)}，使用 standard-json 解码器。 */
    public static List<DeviceCredentials> provision(ConduitApiClient api, String productName, int count) {
        return provision(api, productName, count, "standard-json");
    }

    /**
     * 通过 REST 准备 count 台演示设备并返回其凭据：复用已有设备（按 deviceKey 排序取前 count 台，必要时改名并重置密钥），
     * 不足则新建；已有设备多于 count 时多余的保持不动。产品不存在时按 codec 创建。
     */
    public static List<DeviceCredentials> provision(ConduitApiClient api, String productName, int count,
                                                    String codec) {
        String productKey = api.findOrCreateProduct(productName, codec);
        List<DeviceSummary> existing = new ArrayList<>(api.listDevices(productKey));
        existing.sort(Comparator.comparing(DeviceSummary::deviceKey));

        List<DeviceCredentials> result = new ArrayList<>(count);
        for (DeviceSummary device : existing.subList(0, Math.min(count, existing.size()))) {
            String expected = deviceName(result.size());
            if (!expected.equals(device.name())) {
                api.renameDevice(device.deviceKey(), expected, device.status());
            }
            result.add(api.resetSecret(productKey, device.deviceKey()));
        }
        for (int i = result.size(); i < count; i++) {
            result.add(api.createDevice(productKey, deviceName(i)));
        }
        return result;
    }

    /** 第 i 台（从 0 开始）演示设备的名称：每栋楼 10 台电表，如 0 →「1号楼配电室 电表01」。 */
    public static String deviceName(int index) {
        return "%d号楼配电室 电表%02d".formatted(index / 10 + 1, index % 10 + 1);
    }

    /** 产品还没有任何属性时导入演示物模型（合并模式）；返回是否导入。 */
    public static boolean ensureThingModel(ConduitApiClient api, String productKey) {
        if (api.propertyCount(productKey) > 0) {
            return false;
        }
        api.importThingModel(productKey, loadDemoThingModel(), "merge");
        return true;
    }

    /** 从 classpath 读取打包的演示物模型；找不到时抛 IllegalStateException。 */
    static JsonNode loadDemoThingModel() {
        try (InputStream in = DemoProvisioner.class.getClassLoader().getResourceAsStream(DEMO_THING_MODEL)) {
            if (in == null) {
                throw new IllegalStateException(DEMO_THING_MODEL + " not found on the classpath");
            }
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
