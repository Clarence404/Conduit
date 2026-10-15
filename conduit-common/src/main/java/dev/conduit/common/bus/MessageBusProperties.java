package dev.conduit.common.bus;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 消息总线配置（前缀 conduit.bus）。
 *
 * @param streamKey  上行消息流的 Redis key
 * @param deadKey    死信流的 Redis key
 * @param maxLen     上行流的近似最大长度（超出后旧消息被裁剪）
 * @param deadMaxLen 死信流的近似最大长度
 */
@ConfigurationProperties(prefix = "conduit.bus")
public record MessageBusProperties(
        @DefaultValue("conduit:stream:up") String streamKey,
        @DefaultValue("conduit:stream:dead") String deadKey,
        @DefaultValue("1000000") long maxLen,
        @DefaultValue("100000") long deadMaxLen) {
}
