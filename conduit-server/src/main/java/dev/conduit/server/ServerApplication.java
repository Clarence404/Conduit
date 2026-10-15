package dev.conduit.server;

import dev.conduit.common.bus.MessageBusConfiguration;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * conduit-server 进程入口：从 Redis Streams 总线消费上行消息，走 解码 -> 物模型校验 -> 写 InfluxDB / 最新值 Redis
 * 流水线；同时提供 UI 使用的 REST 接口，并以 PostgreSQL 存租户、产品、物模型、设备与用户。
 * 排除默认的 UserDetailsService 自动配置（认证完全由 JWT 过滤器负责，避免生成随机默认密码）。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@Import(MessageBusConfiguration.class)
@MapperScan(basePackages = "dev.conduit.server", annotationClass = Mapper.class)
public class ServerApplication {

    /** 启动 server 进程。 */
    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
