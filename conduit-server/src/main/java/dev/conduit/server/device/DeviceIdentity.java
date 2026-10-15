package dev.conduit.server.device;

/** 鉴权与流水线需要的设备身份，跨租户查询，缓存在 conduit:auth:{deviceKey}。 */
public record DeviceIdentity(String deviceKey, long tenantId, String status,
                             String secretHash, String secretSalt, String productKey) {

    /**
     * 设备是否处于启用状态；禁用的设备不应通过鉴权，其上报也不应被处理。
     */
    public boolean enabled() {
        return Device.ENABLED.equals(status);
    }
}
