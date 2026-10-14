package dev.conduit.server.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;

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

    @Override
    public void start() {
        running = true;
        thread = Thread.ofVirtual().name("pipeline-worker").start(this::loop);
        log.info("Pipeline worker started as consumer {}", props.consumer());
    }

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
