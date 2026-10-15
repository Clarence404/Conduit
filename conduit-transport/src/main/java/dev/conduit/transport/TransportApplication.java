package dev.conduit.transport;

import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.MessageBusConfiguration;
import dev.conduit.transport.mqtt.MqttTransportProperties;
import dev.conduit.transport.mqtt.MqttProtocolAdapter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;

/**
 * conduit-transport 进程入口：接入层。订阅 EMQX 上的设备消息，组装为 RawMessage 写入
 * Redis Streams 总线，不做任何业务解析（解码与校验由 conduit-server 流水线完成）。
 */
@SpringBootApplication
@Import(MessageBusConfiguration.class)
@EnableConfigurationProperties(MqttTransportProperties.class)
public class TransportApplication {

    /** 启动 transport 进程。 */
    public static void main(String[] args) {
        SpringApplication.run(TransportApplication.class, args);
    }

    /** 注册 MQTT 协议适配器；生命周期（启动/停止）由 {@link dev.conduit.transport.adapter.AdapterLifecycle} 统一驱动。 */
    @Bean
    MqttProtocolAdapter mqttProtocolAdapter(MqttTransportProperties props, MessageBus bus) {
        return new MqttProtocolAdapter(props, bus, Clock.systemUTC());
    }
}
