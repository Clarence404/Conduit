package dev.conduit.server.pipeline;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 流水线的单步执行入口：从 Redis Streams 总线读取一批投递并交给 {@link PipelineProcessor}。
 * PipelineWorker 循环调用，测试中直接调用。
 */
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

    /** 启动时确保消费组存在（幂等）。 */
    @PostConstruct
    void ensureGroup() {
        bus.ensureGroup(props.group());
    }

    /**
     * 读取并处理一批新消息（最长阻塞 props.block）。
     *
     * @return 本次读到的投递数，0 表示无新消息
     */
    public int pollOnce() {
        List<Delivery> batch = guarded(
                () -> bus.read(props.group(), props.consumer(), props.batchSize(), props.block()));
        if (!batch.isEmpty()) {
            processor.process(batch);
        }
        return batch.size();
    }

    /**
     * 认领并处理一批空闲超过 claimMinIdle 的待确认投递（其他实例崩溃或本实例未 ACK 遗留的），不阻塞。
     *
     * @return 本次认领的投递数，0 表示没有可认领的
     */
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

    /** 沿 cause 链判断是否为 Redis 的 NOGROUP 错误（消费组不存在）。 */
    private static boolean isNoGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("NOGROUP")) {
                return true;
            }
        }
        return false;
    }
}
