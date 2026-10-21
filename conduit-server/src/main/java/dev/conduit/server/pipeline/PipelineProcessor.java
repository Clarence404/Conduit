package dev.conduit.server.pipeline;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.pipeline.TelemetryIngestor.Prepared;
import dev.conduit.server.pipeline.codec.DecoderRegistry;
import dev.conduit.server.presence.PresenceHandler;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.TelemetryWriter;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.thingmodel.ThingModelCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 处理一批投递：属性消息交给 {@link TelemetryIngestor}（解码 → 校验 → 写 InfluxDB → 写最新值），本类只负责消费侧的事——
 * 死信、ACK 与在线状态。被剔除的测点另写一条 FIELD_REJECTED 死信记录。不可恢复的错误进死信（并 ACK）；
 * 下游不可用时由 {@link Backoff} 原地无上限退避重试，未处理的投递保持未 ACK，之后由认领机制重新投递。
 * 属性数据落库并 ACK 后，按数据来源批量把设备标记为在线（尽力而为，失败不影响 ACK；规则见 PresenceStore）。
 * 重复投递由 InfluxDB 同 ts 覆盖与最新值按 ts 比较吸收。
 */
@Component
public class PipelineProcessor {

    private static final Logger log = LoggerFactory.getLogger(PipelineProcessor.class);

    /** 已通过解码与校验、等待批量写库的属性消息：原投递与处理结果。 */
    private record PendingProperty(Delivery delivery, Prepared prepared) {
    }

    private final MessageBus bus;
    private final PipelineProperties props;
    private final TelemetryIngestor ingestor;
    private final PresenceHandler presenceHandler;
    private final PresenceStore presence;
    private final MeterRegistry meters;
    private final Backoff backoff;

    @Autowired
    public PipelineProcessor(MessageBus bus, PipelineProperties props, TelemetryIngestor ingestor,
                             PresenceHandler presenceHandler, PresenceStore presence, MeterRegistry meters) {
        this(bus, props, ingestor, presenceHandler, presence, meters,
                Backoff.unlimited(Duration.ofSeconds(1), Duration.ofSeconds(30)));
    }

    /** 可指定退避策略的构造器（供另外两个构造器委托）。 */
    PipelineProcessor(MessageBus bus, PipelineProperties props, TelemetryIngestor ingestor,
                      PresenceHandler presenceHandler, PresenceStore presence, MeterRegistry meters,
                      Backoff backoff) {
        this.bus = bus;
        this.props = props;
        this.ingestor = ingestor;
        this.presenceHandler = presenceHandler;
        this.presence = presence;
        this.meters = meters;
        this.backoff = backoff;
    }

    /**
     * 测试构造器：沿用提取 TelemetryIngestor 之前的协作者列表，内部自行组装 ingestor 与快速退避，
     * 这样既能单独 mock 各协作者，又不必在测试里重复装配。
     */
    PipelineProcessor(MessageBus bus, PipelineProperties props, DeviceIdentityLookup identities,
                      ThingModelCache thingModels, DecoderRegistry decoders, PresenceHandler presenceHandler,
                      TelemetryWriter writer, LatestValueStore latest, PresenceStore presence,
                      MeterRegistry meters, Duration initialBackoff, Duration maxBackoff) {
        this(bus, props, new TelemetryIngestor(identities, thingModels, decoders, writer, latest, meters),
                presenceHandler, presence, meters, Backoff.unlimited(initialBackoff, maxBackoff));
    }

    /**
     * 处理一批投递：逐条分流，在线/离线消息处理后统一 ACK，属性消息攒成一批后统一写库。
     * 每条投递的去向：投递次数超过 maxDeliveries 进 MAX_DELIVERY_EXCEEDED；信封损坏进 MALFORMED_ENVELOPE；
     * EVENT 暂不支持，进 UNSUPPORTED_TYPE；DecodeException 按其原因进死信；其他运行时异常只让该条进 PROCESSING_ERROR，
     * 不影响同批其他消息。
     *
     * @throws ProcessingAbortedException 线程在退避重试中被中断；已读取但未处理的投递保持未 ACK，之后重新认领
     */
    public void process(List<Delivery> batch) {
        log.debug("Processing batch of {} deliveries", batch.size());
        List<String> presenceAcks = new ArrayList<>();
        List<PendingProperty> properties = new ArrayList<>();

        for (Delivery delivery : batch) {
            if (delivery.deliveryCount() > props.maxDeliveries()) {
                dead(delivery, DeadReason.MAX_DELIVERY_EXCEEDED);
                continue;
            }
            RawMessage message;
            try {
                message = delivery.message();
            } catch (IllegalArgumentException e) {
                dead(delivery, DeadReason.MALFORMED_ENVELOPE);
                continue;
            }
            try {
                switch (message.type()) {
                    case PROPERTY -> properties.add(new PendingProperty(delivery,
                            backoff.run("lookup", () -> ingestor.prepare(delivery.id(), message, IngestSource.LIVE))));
                    case PRESENCE_CONNECTED, PRESENCE_DISCONNECTED -> {
                        log.debug("Presence message {}: deviceKey={} type={}", delivery.id(), message.deviceKey(),
                                message.type());
                        backoff.run("presence", () -> {
                            presenceHandler.handle(message);
                            return null;
                        });
                        presenceAcks.add(delivery.id());
                        meters.counter("conduit.pipeline.processed", "type", "presence").increment();
                    }
                    case EVENT -> dead(delivery, DeadReason.UNSUPPORTED_TYPE);
                }
            } catch (DecodeException e) {
                log.debug("Dead-lettering {}: {} {}", delivery.id(), e.reason(), e.getMessage());
                dead(delivery, e.reason(), e.details());
            } catch (ProcessingAbortedException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Unexpected failure processing delivery {}", delivery.id(), e);
                dead(delivery, DeadReason.PROCESSING_ERROR);
            }
        }

        ack(presenceAcks);
        if (!properties.isEmpty()) {
            writeProperties(properties);
        }
    }

    /**
     * 批量写 InfluxDB，再逐条更新最新值、补写 FIELD_REJECTED 记录并 ACK。
     * InfluxDB 拒绝的数据点（字段类型冲突）对应的投递进 FIELD_TYPE_CONFLICT；
     * 单条收尾阶段的意外错误只让该条进 PROCESSING_ERROR。其余投递在写库成功后才一次性 ACK（至少一次投递）。
     */
    private void writeProperties(List<PendingProperty> items) {
        List<TelemetryPoint> points = items.stream().map(i -> i.prepared().point()).toList();
        long writeStart = System.nanoTime();
        WriteResult result = backoff.run("influx write", () -> ingestor.write(points));
        log.debug("Influx write: lines={} rejected={} elapsedMs={}", points.size(), result.rejected().size(),
                (System.nanoTime() - writeStart) / 1_000_000);
        Set<Integer> rejected = result.rejected().stream()
                .map(WriteResult.Rejected::index).collect(Collectors.toSet());

        List<String> acks = new ArrayList<>();
        Map<String, Long> reportedAt = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            PendingProperty item = items.get(i);
            if (rejected.contains(i)) {
                dead(item.delivery(), DeadReason.FIELD_TYPE_CONFLICT);
                continue;
            }
            TelemetryPoint p = item.prepared().point();
            try {
                backoff.run("latest update", () -> {
                    ingestor.updateLatest(p);
                    return null;
                });
                log.debug("Latest values updated: deviceKey={} fields={} ts={}", p.deviceKey(), p.fields().size(),
                        p.ts());
                if (!item.prepared().rejected().isEmpty()) {
                    Map<String, String> details = RejectedFields.details(item.prepared().rejected());
                    // 先留痕再 ACK：两步之间崩溃只会产生重复死信记录，不会丢失
                    backoff.run("dead record", () -> {
                        bus.recordDead(item.delivery(), DeadReason.FIELD_REJECTED.name(), details);
                        return null;
                    });
                }
                acks.add(item.delivery().id());
                reportedAt.merge(p.deviceKey(), item.delivery().message().receivedAt(), Math::max);
            } catch (ProcessingAbortedException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Unexpected failure finishing delivery {}", item.delivery().id(), e);
                dead(item.delivery(), DeadReason.PROCESSING_ERROR);
            }
        }
        ack(acks);
        markOnline(reportedAt);
        meters.counter("conduit.pipeline.processed", "type", "property").increment(acks.size());
    }

    /**
     * 把本批成功落库并已 ACK 的设备按「数据」来源标记为在线（{@link PresenceStore#applyDataBatch}，一次 pipeline 往返）：
     * 补偿「设备在 transport 订阅之前就已连接、没有收到连接事件」的情况。时间取该设备本批消息的最大 receivedAt
     * （transport 主机时钟，与 EMQX 事件时钟不同源），因此数据<b>不会推进</b>乱序保护用的 since：已在线不变，
     * 离线设备只有数据时间超过断连时间加宽限期才会复活，晚到的断连事件仍然生效。
     * 必须在 ACK 之后执行；这是尽力而为的附带动作，Redis 失败只在每批记一次 WARN，不重试、不影响 ACK。
     * 遥测重放不走这条路径：重放的是旧数据，会把已离线设备误标为在线（spec 4.3）。
     */
    private void markOnline(Map<String, Long> reportedAt) {
        if (reportedAt.isEmpty()) {
            return;
        }
        try {
            presence.applyDataBatch(reportedAt);
        } catch (RuntimeException e) {
            log.warn("Failed to mark {} device(s) online from data (best effort, ignored): {}", reportedAt.size(),
                    e.getMessage());
        }
    }

    /** 批量 ACK（空列表直接返回），暂时性故障原地重试。 */
    private void ack(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        backoff.run("ack", () -> {
            bus.ack(props.group(), ids);
            return null;
        });
        log.debug("Acked {} deliveries", ids.size());
    }

    /**
     * 无详情的死信。DEBUG 记一行；PROCESSING_ERROR 的调用方已记 ERROR（含堆栈），这里不再重复。
     * 带详情的重载由调用方（DecodeException 分支）已记 DEBUG。
     */
    private void dead(Delivery delivery, DeadReason reason) {
        if (reason != DeadReason.PROCESSING_ERROR) {
            log.debug("Dead-lettering {}: {}", delivery.id(), reason);
        }
        dead(delivery, reason, Map.of());
    }

    /** 写死信记录、ACK 原投递并计数 conduit.pipeline.dead；details 写入死信记录。 */
    private void dead(Delivery delivery, DeadReason reason, Map<String, String> details) {
        // 先留痕再 ACK，分两步各自重试：ACK 暂时失败时不会重复写死信记录
        backoff.run("dead letter", () -> {
            bus.recordDead(delivery, reason.name(), details);
            return null;
        });
        ack(List.of(delivery.id()));
        meters.counter("conduit.pipeline.dead", "reason", reason.name()).increment();
    }
}
