package dev.conduit.server.presence;

/**
 * 设备在线状态快照（存于 Redis，由 EMQX 上下线事件与设备上报数据共同维护，见 {@link PresenceStore}）。
 *
 * @param online   当前是否在线
 * @param since    乱序保护用的状态时间戳（毫秒）：事件产生的状态取 EMQX 事件时间（上线或下线时刻）；
 *                 由数据产生的状态（无记录时推断上线、离线后超过宽限期重新上线）取 transport 接收时间。
 *                 数据永远不会推进已在线记录的 since，所以它不是「最近上报时间」
 * @param clientIp 事件中携带的客户端 IP，未知（或仅由数据推断上线）为空串
 */
public record Presence(boolean online, long since, String clientIp) {
}
