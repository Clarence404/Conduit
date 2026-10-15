package dev.conduit.server.pipeline;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.pipeline.codec.DecodeContext;
import dev.conduit.server.pipeline.codec.DecodedPayload;
import dev.conduit.server.pipeline.codec.DecoderRegistry;
import dev.conduit.server.pipeline.codec.PayloadDecoder;
import dev.conduit.server.presence.PresenceHandler;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.TelemetryUnavailableException;
import dev.conduit.server.telemetry.TelemetryWriter;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.thingmodel.ProductProfile;
import dev.conduit.server.thingmodel.ThingModelCache;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 处理一批投递。属性消息：身份检查 → 物模型缓存取产品配置 → 按 codec 解码 → 逐测点校验 → 写 InfluxDB 与最新值 → ACK；
 * 被剔除的测点另写一条 FIELD_REJECTED 死信记录。不可恢复的错误进死信（并 ACK）；下游不可用时原地退避重试，
 * 未处理的投递保持未 ACK，之后由认领机制重新投递。属性数据成功落库后顺带把设备标记为在线（尽力而为，失败不影响 ACK）。重复投递由 InfluxDB 同 ts 覆盖与最新值按 ts 比较吸收。
 */
@Component
public class PipelineProcessor {

    private static final Logger log = LoggerFactory.getLogger(PipelineProcessor.class);

    /** 已通过解码与校验、等待批量写库的属性消息：投递、待写数据点、被剔除的测点。 */
    private record PendingProperty(Delivery delivery, TelemetryPoint point, List<RejectedField> rejected) {
    }

    private final MessageBus bus;
    private final PipelineProperties props;
    private final DeviceIdentityLookup identities;
    private final ThingModelCache thingModels;
    private final DecoderRegistry decoders;
    private final PresenceHandler presenceHandler;
    private final TelemetryWriter writer;
    private final LatestValueStore latest;
    private final PresenceStore presence;
    private final MeterRegistry meters;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    /** 处理被中断（如 worker 停止）：中止整批，已读取的投递保持未 ACK。 */
    static class ProcessingAbortedException extends RuntimeException {
        ProcessingAbortedException(String message) {
            super(message);
        }
    }

    @Autowired
    public PipelineProcessor(MessageBus bus, PipelineProperties props, DeviceIdentityLookup identities,
                             ThingModelCache thingModels, DecoderRegistry decoders, PresenceHandler presenceHandler,
                             TelemetryWriter writer, LatestValueStore latest, PresenceStore presence,
                             MeterRegistry meters) {
        this(bus, props, identities, thingModels, decoders, presenceHandler, writer, latest, presence, meters,
                Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    /** 可指定退避时间的构造器，测试用（避免真实等待）。 */
    PipelineProcessor(MessageBus bus, PipelineProperties props, DeviceIdentityLookup identities,
                      ThingModelCache thingModels, DecoderRegistry decoders, PresenceHandler presenceHandler,
                      TelemetryWriter writer, LatestValueStore latest, PresenceStore presence,
                      MeterRegistry meters, Duration initialBackoff, Duration maxBackoff) {
        this.bus = bus;
        this.props = props;
        this.identities = identities;
        this.thingModels = thingModels;
        this.decoders = decoders;
        this.presenceHandler = presenceHandler;
        this.writer = writer;
        this.latest = latest;
        this.presence = presence;
        this.meters = meters;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
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
                    case PROPERTY -> properties.add(retry("lookup", () -> toPending(delivery, message)));
                    case PRESENCE_CONNECTED, PRESENCE_DISCONNECTED -> {
                        retry("presence", () -> {
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
     * 属性消息的「解码 → 校验」阶段：依次检查设备存在、已启用、productKey 一致，取产品物模型与 codec 并解码、逐测点校验。
     * 被剔除的测点计入指标，并随 PendingProperty 带到写库之后留痕。
     *
     * @throws DecodeException UNKNOWN_DEVICE / DEVICE_DISABLED / PRODUCT_MISMATCH / UNKNOWN_CODEC、解码失败，
     *                         或全部测点被拒绝（FIELD_REJECTED，details 含被拒详情）
     */
    private PendingProperty toPending(Delivery delivery, RawMessage message) {
        DeviceIdentity identity = identities.find(message.deviceKey())
                .orElseThrow(() -> new DecodeException(DeadReason.UNKNOWN_DEVICE, message.deviceKey()));
        if (!identity.enabled()) {
            throw new DecodeException(DeadReason.DEVICE_DISABLED, message.deviceKey());
        }
        if (!identity.productKey().equals(message.productKey())) {
            throw new DecodeException(DeadReason.PRODUCT_MISMATCH,
                    message.productKey() + " != " + identity.productKey());
        }
        ProductProfile profile = thingModels.find(identity.productKey())
                .orElseThrow(() -> new DecodeException(DeadReason.UNKNOWN_DEVICE,
                        "product not found: " + identity.productKey()));
        PayloadDecoder decoder = decoders.find(profile.codec())
                .orElseThrow(() -> new DecodeException(DeadReason.UNKNOWN_CODEC, String.valueOf(profile.codec())));
        DecodedPayload decoded = decoder.decode(
                new DecodeContext(identity.productKey(), identity.deviceKey(), message.receivedAt()),
                message.payload());
        FieldValidation validation = FieldValidator.validate(profile.properties(), decoded.values());
        validation.rejected().forEach(r ->
                meters.counter("conduit.pipeline.rejected_fields", "reason", r.reason().name()).increment());
        if (validation.accepted().isEmpty()) {
            throw new DecodeException(DeadReason.FIELD_REJECTED, "all fields rejected",
                    RejectedFields.details(validation.rejected()));
        }
        TelemetryPoint point = new TelemetryPoint(identity.tenantId(), identity.productKey(), identity.deviceKey(),
                decoded.ts(), validation.accepted());
        return new PendingProperty(delivery, point, validation.rejected());
    }

    /**
     * 批量写 InfluxDB，再逐条更新最新值、补写 FIELD_REJECTED 记录并 ACK。
     * InfluxDB 拒绝的数据点（字段类型冲突）对应的投递进 FIELD_TYPE_CONFLICT；
     * 单条收尾阶段的意外错误只让该条进 PROCESSING_ERROR。其余投递在写库成功后才一次性 ACK（至少一次投递）。
     */
    private void writeProperties(List<PendingProperty> items) {
        List<TelemetryPoint> points = items.stream().map(PendingProperty::point).toList();
        WriteResult result = retry("influx write", () -> writer.write(points));
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
            TelemetryPoint p = item.point();
            try {
                retry("latest update", () -> {
                    latest.update(p.deviceKey(), p.ts(), p.fields());
                    return null;
                });
                if (!item.rejected().isEmpty()) {
                    Map<String, String> details = RejectedFields.details(item.rejected());
                    // 先留痕再 ACK：两步之间崩溃只会产生重复死信记录，不会丢失
                    retry("dead record", () -> {
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
     * 把本批成功落库的设备标记为在线：上报数据说明设备正在连接，补偿「设备在 transport 订阅之前就已连接、没有收到连接事件」的情况。
     * 事件时间取该设备本批消息的最大 receivedAt，交给 Lua 的乱序保护：更晚的断连事件不会被覆盖，断连之前收到的数据也不会让设备复活。
     * 客户端 IP 传 null（属性消息不带 IP），Lua 在上线且 IP 为空时保留原有 IP。
     * 每个设备每批只写一次；这是尽力而为的附带动作，Redis 失败只在每批记一次 WARN，不重试、不影响 ACK。
     */
    private void markOnline(Map<String, Long> reportedAt) {
        int failed = 0;
        RuntimeException first = null;
        for (Map.Entry<String, Long> e : reportedAt.entrySet()) {
            try {
                presence.apply(e.getKey(), true, e.getValue(), null);
            } catch (RuntimeException ex) {
                failed++;
                if (first == null) {
                    first = ex;
                }
            }
        }
        if (failed > 0) {
            log.warn("Failed to mark {} device(s) online from data (best effort, ignored): {}", failed,
                    first.getMessage());
        }
    }

    /** 批量 ACK（空列表直接返回），暂时性故障原地重试。 */
    private void ack(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        retry("ack", () -> {
            bus.ack(props.group(), ids);
            return null;
        });
    }

    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * 仅暂时性故障值得原地重试；其余（语法错误、完整性冲突、WRONGTYPE 等）重试也不会成功。
     * 沿 cause 链查找：MyBatis 会把取连接失败（CannotGetJdbcConnectionException）包装成
     * MyBatisSystemException（UncategorizedDataAccessException），只看顶层会把数据库宕机误判为不可恢复。
     */
    private static boolean isRecoverable(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof TelemetryUnavailableException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof TransientDataAccessException
                    || current instanceof RecoverableDataAccessException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    /** 下游暂时不可用时原地指数退避重试，不重新读取，因此不消耗投递次数。 */
    /**
     * 原地执行 action：仅可恢复错误（见 isRecoverable）才退避重试，延迟从 initialBackoff 起翻倍、封顶 maxBackoff，无限重试；
     * 其余异常原样抛出。
     *
     * @param step 步骤名，仅用于日志
     * @throws ProcessingAbortedException 等待期间线程被中断（会恢复中断标志）
     */
    private <T> T retry(String step, Supplier<T> action) {
        long delay = initialBackoff.toMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!isRecoverable(e)) {
                    throw e;
                }
                log.warn("{} unavailable (attempt {}), retrying in {} ms: {}", step, attempt, delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ProcessingAbortedException("Interrupted while retrying " + step);
                }
                delay = Math.min(delay * 2, maxBackoff.toMillis());
            }
        }
    }

    private void dead(Delivery delivery, DeadReason reason) {
        dead(delivery, reason, Map.of());
    }

    /** 写死信记录、ACK 原投递并计数 conduit.pipeline.dead；details 写入死信记录。 */
    private void dead(Delivery delivery, DeadReason reason, Map<String, String> details) {
        // 先留痕再 ACK，分两步各自重试：ACK 暂时失败时不会重复写死信记录
        retry("dead letter", () -> {
            bus.recordDead(delivery, reason.name(), details);
            return null;
        });
        ack(List.of(delivery.id()));
        meters.counter("conduit.pipeline.dead", "reason", reason.name()).increment();
    }
}
