package dev.conduit.server.pipeline;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/** 单步执行入口：PipelineWorker 循环调用，测试中直接调用。 */
@Component
public class PipelineRunner {

    private final MessageBus bus;
    private final PipelineProperties props;
    private final PipelineProcessor processor;

    public PipelineRunner(MessageBus bus, PipelineProperties props, PipelineProcessor processor) {
        this.bus = bus;
        this.props = props;
        this.processor = processor;
    }

    @PostConstruct
    void ensureGroup() {
        bus.ensureGroup(props.group());
    }

    public int pollOnce() {
        List<Delivery> batch = guarded(
                () -> bus.read(props.group(), props.consumer(), props.batchSize(), props.block()));
        if (!batch.isEmpty()) {
            processor.process(batch);
        }
        return batch.size();
    }

    public int claimOnce() {
        List<Delivery> batch = guarded(
                () -> bus.claimStale(props.group(), props.consumer(), props.claimMinIdle(), props.batchSize()));
        if (!batch.isEmpty()) {
            processor.process(batch);
        }
        return batch.size();
    }

    /** Redis 重启丢失消费组时自愈：重建后抛出，由 worker 退避重试。 */
    private List<Delivery> guarded(Supplier<List<Delivery>> source) {
        try {
            return source.get();
        } catch (RuntimeException e) {
            if (isNoGroup(e)) {
                bus.ensureGroup(props.group());
            }
            throw e;
        }
    }

    private static boolean isNoGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("NOGROUP")) {
                return true;
            }
        }
        return false;
    }
}
