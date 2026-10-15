package dev.conduit.transport.adapter;

/**
 * 协议适配器：把某种设备协议的消息转换为 RawMessage 投递到总线。
 * 新增协议（HTTP、Modbus TCP 等）只需新增一个实现。
 */
public interface ProtocolAdapter {

    String name();

    void start();

    void stop();
}
