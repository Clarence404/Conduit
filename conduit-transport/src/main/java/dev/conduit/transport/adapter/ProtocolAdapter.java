package dev.conduit.transport.adapter;

/**
 * 协议适配器：把某种设备协议的消息转换为 RawMessage 投递到总线。
 * 新增协议（HTTP、Modbus TCP 等）只需新增一个实现。
 */
public interface ProtocolAdapter {

    /** 适配器名称，用于日志。 */
    String name();

    /** 建立与设备侧（如 EMQX）的连接并开始接收；就绪后才返回，失败抛异常。 */
    void start();

    /** 停止接收并释放连接，需可安全调用。 */
    void stop();
}
