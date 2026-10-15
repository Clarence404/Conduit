package dev.conduit.server.pipeline.codec;

/**
 * 把设备上报的原始字节翻译为「时间戳 + 测点值」。只做格式翻译，不按物模型转换类型：
 * 整数为 Long（超出 long 为 BigInteger）、小数为 Double、文本 String、布尔 Boolean。
 * 失败抛 DecodeException（MALFORMED_JSON / INVALID_PARAMS / TS_OUT_OF_RANGE）。
 * 新增格式：实现本接口并注册为 @Component，name() 全局唯一。
 */
public interface PayloadDecoder {

    /** 解码器标识，即产品 codec 字段存储的值；全局唯一，重名使启动失败（见 DecoderRegistry）。 */
    String name();

    /** 下拉框显示名。 */
    String displayName();

    /**
     * 把原始报文解码为时间戳与测点值。应无状态、线程安全，由流水线线程直接调用。
     *
     * @param ctx     消息上下文（含接收时间，用于缺省 ts）
     * @param payload 设备上报的原始字节
     * @throws DecodeException 报文无法解码；属于不可恢复错误，消息进死信
     */
    DecodedPayload decode(DecodeContext ctx, byte[] payload);
}
