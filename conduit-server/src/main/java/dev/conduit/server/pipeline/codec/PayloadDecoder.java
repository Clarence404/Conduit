package dev.conduit.server.pipeline.codec;

/**
 * 把设备上报的原始字节翻译为「时间戳 + 测点值」。只做格式翻译，不按物模型转换类型：
 * 整数为 Long（超出 long 为 BigInteger）、小数为 Double、文本 String、布尔 Boolean。
 * 失败抛 DecodeException（MALFORMED_JSON / INVALID_PARAMS / TS_OUT_OF_RANGE）。
 * 新增格式：实现本接口并注册为 @Component，name() 全局唯一。
 */
public interface PayloadDecoder {

    /** 产品 codec 列存的值。 */
    String name();

    /** 下拉框显示名。 */
    String displayName();

    DecodedPayload decode(DecodeContext ctx, byte[] payload);
}
