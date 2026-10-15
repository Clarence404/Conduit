package dev.conduit.server.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 流水线后台消费线程，随应用生命周期启停（conduit.pipeline.enabled=false 时不创建）。
 * 在一条虚拟线程中循环：定期认领遗留投递，再读取新消息交给 {@link PipelineRunner}；
 * 循环内出现任何异常（如 Redis 不可用）都按 1s→30s 指数退避后继续，成功一轮后退避复位。
 */
@Component
@ConditionalOnProperty(prefix = "conduit.pipeline", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PipelineWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PipelineWorker.class);
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final PipelineRunner runner;
    private final PipelineProperties props;
    private final Clock clock;

    private volatile boolean running;
    private Thread thread;

    public PipelineWorker(PipelineRunner runner, PipelineProperties props, Clock clock) {
        this.runner = runner;
        this.props = props;
        this.clock = clock;
    }

    /** 启动消费线程。 */
    @Override
    public void start() {
        running = true;
        thread = Thread.ofVirtual().name("pipeline-worker").start(this::loop);
        log.info("Pipeline worker started as consumer {}", props.consumer());
    }

    /** 停止循环：中断线程并最多等待 5 秒；正在处理的批次若被中断，未 ACK 的投递留待之后认领。 */
    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 消费主循环：每隔 claimInterval 先把待认领的积压全部认领处理完，再读取新消息。
     * 异常时退避重试；线程被中断或 running 变为 false 时退出。
     */
    private void loop() {
        long nextClaimAt = 0;
        long backoff = INITIAL_BACKOFF_MS;
        while (running) {
            try {
                if (clock.millis() >= nextClaimAt) {
                    while (running && runner.claimOnce() > 0) {
                        // 把积压的待处理消息全部认领完
                    }
                    nextClaimAt = clock.millis() + props.claimInterval().toMillis();
                }
                runner.pollOnce();
                backoff = INITIAL_BACKOFF_MS;
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.warn("Pipeline iteration failed, retrying in {} ms: {}", backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }
}
