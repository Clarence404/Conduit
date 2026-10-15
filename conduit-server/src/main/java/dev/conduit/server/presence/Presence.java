package dev.conduit.server.presence;

/**
 * 设备在线状态快照（存于 Redis，由 EMQX 上下线事件维护）。
 *
 * @param online   当前是否在线
 * @param since    该状态对应的事件时间（毫秒），即上线或下线时刻
 * @param clientIp 事件中携带的客户端 IP，未知为空串
 */
public record Presence(boolean online, long since, String clientIp) {
}
