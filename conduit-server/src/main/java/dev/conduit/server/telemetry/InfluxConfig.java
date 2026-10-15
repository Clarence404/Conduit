package dev.conduit.server.telemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
public class InfluxConfig {

    @Bean(destroyMethod = "close")
    InfluxClient influxClient(InfluxProperties props) {
        return new InfluxClient(URI.create(props.url()), props.token());
    }
}
