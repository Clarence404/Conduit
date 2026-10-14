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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    public PipelineProcessor(MessageBus bus, PipelineProperties props, DeviceIdentityLookup identities,
                             PropertyDecoder decoder, PresenceHandler presenceHandler, TelemetryWriter writer,
                             LatestValueStore latest, MeterRegistry meters) {
        this.bus = bus;
        this.props = props;
        this.identities = identities;
        this.decoder = decoder;
        this.presenceHandler = presenceHandler;
        this.writer = writer;
        this.latest = latest;
        this.meters = meters;
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
                    case PROPERTY -> properties.add(new PendingProperty(delivery, toPoint(message)));
                    case PRESENCE_CONNECTED, PRESENCE_DISCONNECTED -> {
                        presenceHandler.handle(message);
                        presenceAcks.add(delivery.id());
                        meters.counter("conduit.pipeline.processed", "type", "presence").increment();
                    }
                    case EVENT -> dead(delivery, DeadReason.UNSUPPORTED_TYPE);
                }
            } catch (DecodeException e) {
                log.debug("Dead-lettering {}: {} {}", delivery.id(), e.reason(), e.getMessage());
                dead(delivery, e.reason());
            }
        }

        bus.ack(props.group(), presenceAcks);
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
        WriteResult result = writer.write(items.stream().map(PendingProperty::point).toList());
        Set<Integer> rejected = result.rejected().stream()
                .map(WriteResult.Rejected::index).collect(Collectors.toSet());

        List<String> acks = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            PendingProperty item = items.get(i);
            if (rejected.contains(i)) {
                dead(item.delivery(), DeadReason.FIELD_TYPE_CONFLICT);
            } else {
                TelemetryPoint p = item.point();
                latest.update(p.deviceKey(), p.ts(), p.fields());
                acks.add(item.delivery().id());
            }
        }
        bus.ack(props.group(), acks);
        meters.counter("conduit.pipeline.processed", "type", "property").increment(acks.size());
    }

    private void dead(Delivery delivery, DeadReason reason) {
        bus.deadLetter(props.group(), delivery, reason.name());
        meters.counter("conduit.pipeline.dead", "reason", reason.name()).increment();
    }
}
