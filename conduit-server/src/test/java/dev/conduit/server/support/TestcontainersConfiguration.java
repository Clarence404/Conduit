package dev.conduit.server.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.InfluxDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String INFLUX_TOKEN = "test-influx-token";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.8"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4.2")).withExposedPorts(6379);
    }

    @Bean
    InfluxDBContainer<?> influx() {
        return new InfluxDBContainer<>(DockerImageName.parse("influxdb:2.7.11"))
                .withAdminToken(INFLUX_TOKEN)
                .withOrganization("conduit")
                .withBucket("telemetry");
    }

    @Bean
    DynamicPropertyRegistrar influxProperties(InfluxDBContainer<?> influx) {
        return registry -> {
            registry.add("conduit.influx.url", influx::getUrl);
            registry.add("conduit.influx.token", () -> INFLUX_TOKEN);
            registry.add("conduit.influx.org", () -> "conduit");
            registry.add("conduit.influx.bucket", () -> "telemetry");
        };
    }
}
