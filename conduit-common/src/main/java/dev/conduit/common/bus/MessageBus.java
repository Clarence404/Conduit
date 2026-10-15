package dev.conduit.common.bus;

import dev.conduit.common.message.RawMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 接入与处理之间的消息总线。V0.1 由 Redis Streams 实现，后续可替换为 Kafka / RabbitMQ。
 * 语义为至少一次投递：消费者处理成功后必须 ack，否则消息会被重新认领。
 *
 * <p>契约：
 * <ul>
 *   <li>{@link #read} 首次投递的 deliveryCount 为 1；阻塞超时返回空列表。{@code block} 必须小于 Redis
 *       客户端命令超时，否则阻塞读会被客户端当作超时报错。</li>
 *   <li>{@link #claimStale} 在服务端按空闲时间过滤，返回的 deliveryCount 为此前投递次数 + 1。
 *       已被 MAXLEN 裁剪掉的条目不会再出现在结果中（见设计文档 9.3 已知限制）。</li>
 *   <li>{@link #deadLetter} 先写死信流再 ack 原消息；两步之间崩溃会产生重复死信，属于至少一次语义可接受。</li>
 *   <li>{@link #recordDead} 只写死信流、不确认原消息，用于原消息已部分处理成功、仍需留痕的情况。</li>
 * </ul>
 */
public interface MessageBus {

    String publish(RawMessage message);

    void ensureGroup(String group);

    List<Delivery> read(String group, String consumer, int count, Duration block);

    List<Delivery> claimStale(String group, String consumer, Duration minIdle, int count);

    void ack(String group, List<String> ids);

    void deadLetter(String group, Delivery delivery, String reason);

    /** 写一条死信记录（原始字段 + details + reason/originalId/deliveryCount/failedAt），不 ack。 */
    void recordDead(Delivery delivery, String reason, Map<String, String> details);
}
