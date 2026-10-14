package dev.conduit.simulator;

import java.util.ArrayList;
import java.util.List;

/**
 * 准备演示设备：复用同名产品下已有的设备（重置密钥以拿到明文），不足的部分新建。
 * 密钥只在创建/重置时返回一次，所以每次启动都会重置已有设备的密钥。
 */
public final class DemoProvisioner {

    private DemoProvisioner() {
    }

    public static List<DeviceCredentials> provision(ConduitApiClient api, String productName, int count) {
        String productKey = api.findOrCreateProduct(productName);
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
}
