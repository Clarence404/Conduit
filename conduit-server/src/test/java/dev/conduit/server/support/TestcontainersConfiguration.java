package dev.conduit.server.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 集成测试用的容器配置：PostgreSQL、Redis、InfluxDB 3 三个容器，并把连接信息注入 Spring 属性。 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String INFLUX_TOKEN = "apiv3_test-influx-token";

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
    InfluxDb3Container influx() {
        return new InfluxDb3Container(INFLUX_TOKEN);
    }

    @Bean
    DynamicPropertyRegistrar influxProperties(InfluxDb3Container influx) {
        return registry -> {
            registry.add("conduit.influx.url", influx::getUrl);
            registry.add("conduit.influx.token", () -> INFLUX_TOKEN);
            registry.add("conduit.influx.database", () -> InfluxTestSupport.DATABASE);
        };
    }
}
