package dev.conduit.transport.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把所有 {@link ProtocolAdapter} 挂到 Spring 生命周期上：容器启动完成时依次 start，关闭时 stop。
 * 任一适配器 start 抛异常会使 transport 进程启动失败。
 */
@Component
public class AdapterLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AdapterLifecycle.class);

    private final List<ProtocolAdapter> adapters;
    private volatile boolean running;

    public AdapterLifecycle(List<ProtocolAdapter> adapters) {
        this.adapters = adapters;
    }

    @Override
    public void start() {
        adapters.forEach(a -> {
            log.info("Starting protocol adapter {}", a.name());
            a.start();
        });
        running = true;
    }

    @Override
    public void stop() {
        adapters.forEach(ProtocolAdapter::stop);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
