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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 处理一批投递。属性消息：身份检查 → 物模型缓存取产品配置 → 按 codec 解码 → 逐测点校验 → 写 InfluxDB 与最新值 → ACK；
 * 被剔除的测点另写一条 FIELD_REJECTED 死信记录。不可恢复的错误进死信（并 ACK）；下游不可用时原地退避重试，
 * 未处理的投递保持未 ACK，之后由认领机制重新投递。重复投递由 InfluxDB 同 ts 覆盖与最新值按 ts 比较吸收。
 */
@Component
public class PipelineProcessor {

    private static final Logger log = LoggerFactory.getLogger(PipelineProcessor.class);

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
                             TelemetryWriter writer, LatestValueStore latest, MeterRegistry meters) {
        this(bus, props, identities, thingModels, decoders, presenceHandler, writer, latest, meters,
                Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    PipelineProcessor(MessageBus bus, PipelineProperties props, DeviceIdentityLookup identities,
                      ThingModelCache thingModels, DecoderRegistry decoders, PresenceHandler presenceHandler,
                      TelemetryWriter writer, LatestValueStore latest, MeterRegistry meters,
                      Duration initialBackoff, Duration maxBackoff) {
        this.bus = bus;
        this.props = props;
        this.identities = identities;
        this.thingModels = thingModels;
        this.decoders = decoders;
        this.presenceHandler = presenceHandler;
        this.writer = writer;
        this.latest = latest;
        this.meters = meters;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

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

    private void writeProperties(List<PendingProperty> items) {
        List<TelemetryPoint> points = items.stream().map(PendingProperty::point).toList();
        WriteResult result = retry("influx write", () -> writer.write(points));
        Set<Integer> rejected = result.rejected().stream()
                .map(WriteResult.Rejected::index).collect(Collectors.toSet());

        List<String> acks = new ArrayList<>();
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
            } catch (ProcessingAbortedException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Unexpected failure finishing delivery {}", item.delivery().id(), e);
                dead(item.delivery(), DeadReason.PROCESSING_ERROR);
            }
        }
        ack(acks);
        meters.counter("conduit.pipeline.processed", "type", "property").increment(acks.size());
    }

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
