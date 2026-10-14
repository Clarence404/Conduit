package dev.conduit.server.device;

/** 鉴权与流水线需要的设备身份，跨租户查询，缓存在 conduit:auth:{deviceKey}。 */
public record DeviceIdentity(String deviceKey, long tenantId, String status,
                             String secretHash, String secretSalt, String productKey) {

    public boolean enabled() {
        return Device.ENABLED.equals(status);
    }
}
