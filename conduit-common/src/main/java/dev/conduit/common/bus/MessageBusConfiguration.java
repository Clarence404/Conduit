package dev.conduit.common.bus;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessageBusProperties.class)
public class MessageBusConfiguration {

    @Bean
    public MessageBus messageBus(StringRedisTemplate redis, MessageBusProperties props) {
        return new RedisStreamMessageBus(redis, props);
    }
}
