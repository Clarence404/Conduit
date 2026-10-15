package dev.conduit.server.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 启动时建库并设置保留期。必须先于首次写入：InfluxDB 3 写入不存在的库会自动建库，但不带保留期，之后无法再设置。
 * 用 SmartInitializingSingleton 而不是 ApplicationRunner：它在所有单例创建后、SmartLifecycle（PipelineWorker）
 * 启动前执行，ApplicationRunner 则晚于 worker 启动，会与首批写入竞争。失败则启动失败，由容器重启策略重试。
 */
@Component
public class InfluxDatabaseInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(InfluxDatabaseInitializer.class);

    private final InfluxClient client;
    private final InfluxProperties props;

    public InfluxDatabaseInitializer(InfluxClient client, InfluxProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * 启动时建库；库已存在则保持不变（保留期不会被修改）。InfluxDB 不可用时抛异常使启动失败。
     */
    @Override
    public void afterSingletonsInstantiated() {
        client.createDatabaseIfMissing(props.database(), props.retention());
        log.info("InfluxDB database '{}' ready (retention {} applies only when the database is created)",
                props.database(), props.retention());
    }
}
