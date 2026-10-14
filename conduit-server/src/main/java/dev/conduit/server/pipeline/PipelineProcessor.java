package dev.conduit.server.pipeline;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.presence.PresenceHandler;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.TelemetryWriter;
import dev.conduit.server.telemetry.WriteResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.conduit.server.telemetry.TelemetryUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 处理一批投递。不可恢复的错误进死信（并 ACK）；下游不可用时直接抛出，未处理的投递保持未 ACK，
 * 之后由认领机制重新投递。重复投递由 InfluxDB 同 ts 覆盖与最新值按 ts 比较吸收。
 */
@Component
public class PipelineProcessor {

    private static final Logger log = LoggerFactory.getLogger(PipelineProcessor.class);

    private record PendingProperty(Delivery delivery, TelemetryPoint point) {
    }

    private final MessageBus bus;
    private final PipelineProperties props;
    private final DeviceIdentityLookup identities;
    private final PropertyDecoder decoder;
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
                             PropertyDecoder decoder, PresenceHandler presenceHandler, TelemetryWriter writer,
                             LatestValueStore latest, MeterRegistry meters) {
        this(bus, props, identities, decoder, presenceHandler, writer, latest, meters,
                Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    PipelineProcessor(MessageBus bus, PipelineProperties props, DeviceIdentityLookup identities,
                      PropertyDecoder decoder, PresenceHandler presenceHandler, TelemetryWriter writer,
                      LatestValueStore latest, MeterRegistry meters, Duration initialBackoff, Duration maxBackoff) {
        this.bus = bus;
        this.props = props;
        this.identities = identities;
        this.decoder = decoder;
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
                    case PROPERTY -> properties.add(
                            new PendingProperty(delivery, retry("lookup", () -> toPoint(message))));
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
                dead(delivery, e.reason());
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

    private TelemetryPoint toPoint(RawMessage message) {
        DeviceIdentity identity = identities.find(message.deviceKey())
                .orElseThrow(() -> new DecodeException(DeadReason.UNKNOWN_DEVICE, message.deviceKey()));
        if (!identity.enabled()) {
            throw new DecodeException(DeadReason.DEVICE_DISABLED, message.deviceKey());
        }
        if (!identity.productKey().equals(message.productKey())) {
            throw new DecodeException(DeadReason.PRODUCT_MISMATCH,
                    message.productKey() + " != " + identity.productKey());
        }
        DecodedProperty decoded = decoder.decode(message.payload(), message.receivedAt());
        return new TelemetryPoint(identity.tenantId(), identity.productKey(), identity.deviceKey(),
                decoded.ts(), decoded.params());
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
                acks.add(item.delivery().id());
            } catch (ProcessingAbortedException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Unexpected failure updating latest value for delivery {}", item.delivery().id(), e);
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

    /** 仅暂时性故障值得原地重试；其余（语法错误、完整性冲突、WRONGTYPE 等）重试也不会成功。 */
    private static boolean isRecoverable(Throwable e) {
        return e instanceof TelemetryUnavailableException
                || e instanceof DataAccessResourceFailureException
                || e instanceof TransientDataAccessException
                || e instanceof RecoverableDataAccessException;
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
        retry("dead letter", () -> {
            bus.deadLetter(props.group(), delivery, reason.name());
            return null;
        });
        meters.counter("conduit.pipeline.dead", "reason", reason.name()).increment();
    }
}
