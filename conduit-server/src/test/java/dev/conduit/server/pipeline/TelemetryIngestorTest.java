package dev.conduit.server.pipeline;

import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.pipeline.codec.DecodedPayload;
import dev.conduit.server.pipeline.codec.DecoderRegistry;
import dev.conduit.server.pipeline.codec.PayloadDecoder;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.TelemetryWriter;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.thingmodel.DataType;
import dev.conduit.server.thingmodel.ProductProfile;
import dev.conduit.server.thingmodel.PropertySpec;
import dev.conduit.server.thingmodel.ThingModelCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TelemetryIngestor 单元测试：身份与物模型检查、解码校验后的数据点、全部测点被拒的 FIELD_REJECTED，以及写库与最新值的委托。 */
class TelemetryIngestorTest {

    DeviceIdentityLookup identities = mock(DeviceIdentityLookup.class);
    ThingModelCache thingModels = mock(ThingModelCache.class);
    DecoderRegistry decoders = mock(DecoderRegistry.class);
    PayloadDecoder decoder = mock(PayloadDecoder.class);
    TelemetryWriter writer = mock(TelemetryWriter.class);
    LatestValueStore latest = mock(LatestValueStore.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    TelemetryIngestor ingestor;

    @BeforeEach
    void setUp() {
        ingestor = new TelemetryIngestor(identities, thingModels, decoders, writer, latest, meters);
        when(identities.find("d1")).thenReturn(Optional.of(
                new DeviceIdentity("d1", 7L, "ENABLED", "h", "s", "pk", "ACTIVE")));
        when(thingModels.find("pk")).thenReturn(Optional.of(new ProductProfile("standard-json", Map.of())));
        when(decoders.find("standard-json")).thenReturn(Optional.of(decoder));
        when(decoder.decode(any(), any())).thenReturn(new DecodedPayload(1760400000000L, Map.of("Ua", 220.5)));
    }

    private static RawMessage message(String deviceKey) {
        return new RawMessage("pk", deviceKey, MessageType.PROPERTY,
                "{}".getBytes(StandardCharsets.UTF_8), 1760400000001L, Map.of());
    }

    private void useModel(PropertySpec spec) {
        when(thingModels.find("pk")).thenReturn(Optional.of(
                new ProductProfile("standard-json", Map.of(spec.identifier(), spec))));
    }

    @Test
    void buildsATelemetryPointFromTheDecodedAndValidatedPayload() {
        TelemetryIngestor.Prepared prepared = ingestor.prepare("1-0", message("d1"));

        assertThat(prepared.point()).isEqualTo(
                new TelemetryPoint(7L, "pk", "d1", 1760400000000L, Map.of("Ua", 220.5)));
        assertThat(prepared.rejected()).isEmpty();
    }

    @Test
    void anUnknownDeviceIsReportedAsUnknownDevice() {
        when(identities.find("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestor.prepare("1-0", message("ghost")))
                .isInstanceOf(DecodeException.class)
                .extracting(e -> ((DecodeException) e).reason())
                .isEqualTo(DeadReason.UNKNOWN_DEVICE);
    }

    @Test
    void aMessageWhoseFieldsAreAllRejectedIsReportedAsFieldRejected() {
        useModel(new PropertySpec("Ua", "Ua", DataType.DOUBLE, null, null, null, null, Map.of()));
        when(decoder.decode(any(), any())).thenReturn(new DecodedPayload(1760400000000L, Map.of("Ua", "abc")));

        assertThatThrownBy(() -> ingestor.prepare("1-0", message("d1")))
                .isInstanceOf(DecodeException.class)
                .extracting(e -> ((DecodeException) e).reason())
                .isEqualTo(DeadReason.FIELD_REJECTED);
    }

    @Test
    void writeAndLatestUpdateAreDelegated() {
        TelemetryPoint point = new TelemetryPoint(7L, "pk", "d1", 1760400000000L, Map.of("Ua", 220.5));
        when(writer.write(anyList())).thenReturn(WriteResult.ok());

        assertThat(ingestor.write(List.of(point)).rejected()).isEmpty();
        ingestor.updateLatest(point);

        verify(writer).write(List.of(point));
        verify(latest).update("d1", 1760400000000L, Map.of("Ua", 220.5));
    }
}
