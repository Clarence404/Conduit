package dev.conduit.server.pipeline;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

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
        List<Delivery> batch = bus.read(props.group(), props.consumer(), props.batchSize(), props.block());
        if (!batch.isEmpty()) {
            processor.process(batch);
        }
        return batch.size();
    }

    public int claimOnce() {
        List<Delivery> batch = bus.claimStale(props.group(), props.consumer(), props.claimMinIdle(), props.batchSize());
        if (!batch.isEmpty()) {
            processor.process(batch);
        }
        return batch.size();
    }
}
