package dev.conduit.server.pipeline.codec;

import java.util.Map;

/**
 * 解码器的输出：一次上报的时间戳与测点值，位于「解码」与「物模型校验」两步之间。
 * values 保留报文原始类型（Long/BigInteger/Double/String/Boolean），尚未按物模型转换；保持报文中的键顺序。
 *
 * @param ts 毫秒时间戳，已通过 {@link TimestampPolicy} 校验
 */
public record DecodedPayload(long ts, Map<String, Object> values) {
}
