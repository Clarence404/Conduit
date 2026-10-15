package dev.conduit.simulator;

/**
 * 模拟设备连接 EMQX 所需的凭据（MQTT 用户名与 clientId 均为 deviceKey，密码为 secret）。
 *
 * @param productKey 所属产品 Key，用于拼 topic
 * @param deviceKey  设备 Key
 * @param secret     设备明文密钥
 */
public record DeviceCredentials(String productKey, String deviceKey, String secret) {
}
