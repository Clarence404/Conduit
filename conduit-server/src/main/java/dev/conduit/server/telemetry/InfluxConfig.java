package dev.conduit.server.telemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * InfluxDB 客户端的 Spring 装配：按配置创建全局唯一的 InfluxClient。
 * 位于 conduit-server 流水线的时序存储侧（写入历史数据与历史查询都经过它）。
 */
@Configuration(proxyBeanMethods = false)
public class InfluxConfig {

    /**
     * 创建 InfluxClient；容器关闭时调用 close 释放 HTTP 连接。
     */
    @Bean(destroyMethod = "close")
    InfluxClient influxClient(InfluxProperties props) {
        return new InfluxClient(URI.create(props.url()), props.token());
    }
}
