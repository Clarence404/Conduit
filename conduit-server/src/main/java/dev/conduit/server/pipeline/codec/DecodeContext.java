package dev.conduit.server.pipeline.codec;

/**
 * 解码时可见的消息上下文，传给 {@link PayloadDecoder#decode}。
 * 当前内置解码器只用 receivedAt（报文缺省 ts 时的兜底时间）；
 * productKey/deviceKey 为将来的脚本解码器预留（凭 productKey 找到产品脚本）。
 *
 * @param receivedAt 传输进程（conduit-transport）收到报文的毫秒时间戳
 */
public record DecodeContext(String productKey, String deviceKey, long receivedAt) {
}
