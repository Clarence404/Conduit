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

@SpringBootApplication
@Import(MessageBusConfiguration.class)
@EnableConfigurationProperties(MqttTransportProperties.class)
public class TransportApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransportApplication.class, args);
    }

    @Bean
    MqttProtocolAdapter mqttProtocolAdapter(MqttTransportProperties props, MessageBus bus) {
        return new MqttProtocolAdapter(props, bus, Clock.systemUTC());
    }
}
