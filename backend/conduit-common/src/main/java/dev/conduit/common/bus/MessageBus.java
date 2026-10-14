package dev.conduit.common.bus;

import dev.conduit.common.message.RawMessage;

import java.time.Duration;
import java.util.List;

/**
 * 接入与处理之间的消息总线。V0.1 由 Redis Streams 实现，后续可替换为 Kafka / RabbitMQ。
 * 语义为至少一次投递：消费者处理成功后必须 ack，否则消息会被重新认领。
 */
public interface MessageBus {

    String publish(RawMessage message);

    void ensureGroup(String group);

    List<Delivery> read(String group, String consumer, int count, Duration block);

    List<Delivery> claimStale(String group, String consumer, Duration minIdle, int count);

    void ack(String group, List<String> ids);

    void deadLetter(String group, Delivery delivery, String reason);
}
