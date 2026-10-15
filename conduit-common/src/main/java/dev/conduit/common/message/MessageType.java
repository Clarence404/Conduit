package dev.conduit.common.message;

/**
 * 上行消息类型，由 conduit-transport 根据 MQTT topic 判定，决定 server 流水线走哪条处理分支。
 */
public enum MessageType {
    /** 属性上报（遥测数据），进入解码、物模型校验并写 InfluxDB / 最新值 Redis。 */
    PROPERTY,
    /** 事件上报。 */
    EVENT,
    /** 设备上线（来自 EMQX 连接事件），更新在线状态。 */
    PRESENCE_CONNECTED,
    /** 设备下线（来自 EMQX 断开事件），更新在线状态。 */
    PRESENCE_DISCONNECTED
}
