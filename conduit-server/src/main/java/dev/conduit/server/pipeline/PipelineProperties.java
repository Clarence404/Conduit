package dev.conduit.server.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 流水线配置（前缀 conduit.pipeline）。
 *
 * @param enabled       是否启动 PipelineWorker 消费循环
 * @param group         Redis Streams 消费组名
 * @param consumer      本实例在消费组中的消费者名（多实例须各不相同）
 * @param batchSize     每次读取/认领的最大消息数
 * @param block         读取新消息时的最长阻塞时间
 * @param claimMinIdle  待确认消息空闲多久后可被认领（崩溃实例遗留的投递）
 * @param claimInterval worker 两次认领扫描的最短间隔
 * @param maxDeliveries 单条消息的最大投递次数，超过则进死信 MAX_DELIVERY_EXCEEDED
 */
@ConfigurationProperties(prefix = "conduit.pipeline")
public record PipelineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("conduit-server") String group,
        @DefaultValue("server-1") String consumer,
        @DefaultValue("100") int batchSize,
        @DefaultValue("1s") Duration block,
        @DefaultValue("60s") Duration claimMinIdle,
        @DefaultValue("10s") Duration claimInterval,
        @DefaultValue("5") int maxDeliveries) {
}
