package dev.conduit.server.device;

import dev.conduit.server.tenant.Tenant;

/**
 * 鉴权与流水线需要的设备身份（含所属租户的状态），跨租户查询，缓存在 conduit:auth:{deviceKey}（5 分钟）。
 * tenantStatus 为 V0.2-C 新增：升级前写入缓存的条目没有该字段（反序列化为 null），按租户启用处理，随缓存过期自然消失。
 */
public record DeviceIdentity(String deviceKey, long tenantId, String status,
                             String secretHash, String secretSalt, String productKey, String tenantStatus) {

    /**
     * 设备是否处于启用状态；禁用的设备不应通过鉴权，其上报也不应被处理。
     */
    public boolean enabled() {
        return Device.ENABLED.equals(status);
    }

    /**
     * 所属租户是否启用；停用租户的设备不能接入，已连接设备的上报进 TENANT_DISABLED 死信。null（旧缓存条目）视为启用。
     */
    public boolean tenantActive() {
        return !Tenant.DISABLED.equals(tenantStatus);
    }
}
