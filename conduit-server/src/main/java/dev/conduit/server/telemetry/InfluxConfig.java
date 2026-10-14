package dev.conduit.server.telemetry;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InfluxConfig {

    @Bean(destroyMethod = "close")
    InfluxDBClient influxDBClient(InfluxProperties props) {
        return InfluxDBClientFactory.create(props.url(), props.token().toCharArray(), props.org(), props.bucket());
    }
}
