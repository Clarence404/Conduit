package dev.conduit.common.bus;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 消息总线的 Spring 装配：把 Redis Streams 实现注册为 {@link MessageBus} Bean，
 * 供 conduit-transport（发布）与 conduit-server 流水线（消费）共用。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessageBusProperties.class)
public class MessageBusConfiguration {

    /** 基于 Redis Streams 的总线实现；替换总线只需在此改为其他实现。 */
    @Bean
    public MessageBus messageBus(StringRedisTemplate redis, MessageBusProperties props) {
        return new RedisStreamMessageBus(redis, props);
    }
}
