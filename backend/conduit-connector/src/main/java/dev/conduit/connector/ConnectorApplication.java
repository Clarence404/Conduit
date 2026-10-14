package dev.conduit.connector;

import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.MessageBusConfiguration;
import dev.conduit.connector.mqtt.MqttConnectorProperties;
import dev.conduit.connector.mqtt.MqttProtocolAdapter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;

@SpringBootApplication
@Import(MessageBusConfiguration.class)
@EnableConfigurationProperties(MqttConnectorProperties.class)
public class ConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConnectorApplication.class, args);
    }

    @Bean
    MqttProtocolAdapter mqttProtocolAdapter(MqttConnectorProperties props, MessageBus bus) {
        return new MqttProtocolAdapter(props, bus, Clock.systemUTC());
    }
}
