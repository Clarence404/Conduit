package dev.conduit.server.pipeline.codec;

/** productKey/deviceKey 为将来的脚本解码器预留（凭 productKey 找到产品脚本）。 */
public record DecodeContext(String productKey, String deviceKey, long receivedAt) {
}
