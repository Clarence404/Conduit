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
import java.util.Set;

/**
 * 准备演示设备：复用同名产品下已有的设备（重置密钥以拿到明文），不足的部分新建。
 * 密钥只在创建/重置时返回一次，所以每次启动都会重置已有设备的密钥。
 * 设备按 deviceKey 排序后依次命名为「N号楼配电室 电表MM」，旧名称（如 sim-001）会被改成新名称，Key 不变。
 * 产品缺少演示物模型中的任一属性时，以合并模式导入演示物模型（见 ensureThingModel）。
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

    /**
     * 产品缺少演示物模型（docs/examples/demo-meter.thing-model.json）中的任一属性时，以合并模式导入整个演示物模型：
     * 缺少的属性被新增，已有的演示属性按文件内容更新，产品上的其他属性不受影响。已全部存在时不做任何修改。
     * 已有部署因此会在升级后自动补上新增的演示属性（如 V0.2-B 的运行状态 st）。
     *
     * @return 是否执行了导入
     */
    public static boolean ensureThingModel(ConduitApiClient api, String productKey) {
        JsonNode model = loadDemoThingModel();
        Set<String> existing = api.propertyIdentifiers(productKey);
        for (JsonNode property : model.get("properties")) {
            if (!existing.contains(property.get("identifier").asText())) {
                api.importThingModel(productKey, model, "merge");
                return true;
            }
        }
        return false;
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
