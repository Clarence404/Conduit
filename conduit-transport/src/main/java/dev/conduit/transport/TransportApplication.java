package dev.conduit.transport;

import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.MessageBusConfiguration;
import dev.conduit.transport.adapter.ProtocolAdapter;
import dev.conduit.transport.leader.LeaderElection;
import dev.conduit.transport.leader.LeaderHealthIndicator;
import dev.conduit.transport.leader.LeaderLock;
import dev.conduit.transport.leader.LeaderProperties;
import dev.conduit.transport.leader.RedisLeaderLock;
import dev.conduit.transport.mqtt.MqttProtocolAdapter;
import dev.conduit.transport.mqtt.MqttTransportProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.List;

/**
 * conduit-transport 进程入口：接入层。订阅 EMQX 上的设备消息，组装为 RawMessage 写入 Redis Streams 总线，
 * 不做任何业务解析（解码与校验由 conduit-server 流水线完成）。多个实例单活多备：经 Redis 锁选主，只有主实例连接 EMQX。
 */
@SpringBootApplication
@Import(MessageBusConfiguration.class)
@EnableConfigurationProperties({MqttTransportProperties.class, LeaderProperties.class})
public class TransportApplication {

    /** 启动 transport 进程。 */
    public static void main(String[] args) {
        SpringApplication.run(TransportApplication.class, args);
    }

    /** MQTT 协议适配器；何时连接由 {@link LeaderElection} 决定。 */
    @Bean
    MqttProtocolAdapter mqttProtocolAdapter(MqttTransportProperties props, MessageBus bus) {
        return new MqttProtocolAdapter(props, bus, Clock.systemUTC());
    }

    /** Redis 选主锁，实例 id 为 主机名-进程号-随机串。 */
    @Bean
    LeaderLock leaderLock(StringRedisTemplate redis, LeaderProperties props) {
        return new RedisLeaderLock(redis, props.key(), props.ttl(), RedisLeaderLock.newInstanceId());
    }

    /** 选主协调器（SmartLifecycle，容器启动后开始抢锁，关闭时释放锁）。 */
    @Bean
    LeaderElection leaderElection(LeaderLock lock, List<ProtocolAdapter> adapters, LeaderProperties props) {
        return new LeaderElection(lock, adapters, props);
    }

    /** 健康检查组件 leader：显示角色、实例 id 与连接状态。 */
    @Bean
    LeaderHealthIndicator leaderHealthIndicator(LeaderElection election) {
        return new LeaderHealthIndicator(election);
    }
}
