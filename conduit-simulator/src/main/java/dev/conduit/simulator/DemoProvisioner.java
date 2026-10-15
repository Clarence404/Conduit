package dev.conduit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 准备演示设备：复用同名产品下已有的设备（重置密钥以拿到明文），不足的部分新建。
 * 密钥只在创建/重置时返回一次，所以每次启动都会重置已有设备的密钥。
 */
public final class DemoProvisioner {

    /** 打包自 docs/examples/demo-meter.thing-model.json。 */
    public static final String DEMO_THING_MODEL = "demo-meter.thing-model.json";

    private DemoProvisioner() {
    }

    public static List<DeviceCredentials> provision(ConduitApiClient api, String productName, int count) {
        return provision(api, productName, count, "standard-json");
    }

    public static List<DeviceCredentials> provision(ConduitApiClient api, String productName, int count,
                                                    String codec) {
        String productKey = api.findOrCreateProduct(productName, codec);
        List<String> existing = new ArrayList<>(api.listDeviceKeys(productKey));
        existing.sort(String::compareTo);

        List<DeviceCredentials> result = new ArrayList<>(count);
        for (String deviceKey : existing.subList(0, Math.min(count, existing.size()))) {
            result.add(api.resetSecret(productKey, deviceKey));
        }
        for (int i = result.size(); i < count; i++) {
            result.add(api.createDevice(productKey, "sim-%03d".formatted(i + 1)));
        }
        return result;
    }

    /** 产品还没有任何属性时导入演示物模型（合并模式）；返回是否导入。 */
    public static boolean ensureThingModel(ConduitApiClient api, String productKey) {
        if (api.propertyCount(productKey) > 0) {
            return false;
        }
        api.importThingModel(productKey, loadDemoThingModel(), "merge");
        return true;
    }

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
