package dev.conduit.server.pipeline;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.common.message.RawMessageCodec;
import dev.conduit.server.device.DeviceIdentity;
import dev.conduit.server.device.DeviceIdentityLookup;
import dev.conduit.server.presence.PresenceHandler;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.telemetry.TelemetryUnavailableException;
import dev.conduit.server.telemetry.TelemetryWriter;
import dev.conduit.server.telemetry.WriteResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.jdbc.BadSqlGrammarException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineProcessorTest {

    private static final String GROUP = "g";

    MessageBus bus = mock(MessageBus.class);
    DeviceIdentityLookup identities = mock(DeviceIdentityLookup.class);
    PropertyDecoder decoder = mock(PropertyDecoder.class);
    PresenceHandler presenceHandler = mock(PresenceHandler.class);
    TelemetryWriter writer = mock(TelemetryWriter.class);
    LatestValueStore latest = mock(LatestValueStore.class);
    PipelineProcessor processor;

    @BeforeEach
    void setUp() {
        PipelineProperties props = new PipelineProperties(true, GROUP, "c", 100,
                Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(10), 5);
        processor = new PipelineProcessor(bus, props, identities, decoder, presenceHandler, writer, latest,
                new SimpleMeterRegistry(), Duration.ofMillis(1), Duration.ofMillis(5));
        when(decoder.decode(any(), anyLong())).thenReturn(new DecodedProperty(1L, Map.of("Ua", 1.0)));
        when(writer.write(anyList())).thenReturn(WriteResult.ok());
    }

    private DeviceIdentity identity(String dk) {
        return new DeviceIdentity(dk, 1L, "ENABLED", "h", "s", "pk");
    }

    private Delivery delivery(String id, String dk) {
        RawMessage m = new RawMessage("pk", dk, MessageType.PROPERTY, "{}".getBytes(StandardCharsets.UTF_8), 1L, Map.of());
        return new Delivery(id, RawMessageCodec.toFields(m), 1);
    }

    @Test
    void retriesWriteInPlaceUntilInfluxRecovers() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(writer.write(anyList()))
                .thenThrow(new TelemetryUnavailableException(new RuntimeException("down")))
                .thenThrow(new TelemetryUnavailableException(new RuntimeException("down")))
                .thenReturn(WriteResult.ok());

        processor.process(List.of(delivery("1-0", "d1")));

        verify(writer, times(3)).write(anyList());
        verify(bus, times(1)).ack(GROUP, List.of("1-0"));
        verify(bus, never()).deadLetter(anyString(), any(), anyString());
    }

    @Test
    void retriesLatestUpdateOnRedisFailure() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        doThrow(new RedisConnectionFailureException("down")).doNothing().when(latest).update(eq("d1"), anyLong(), any());

        processor.process(List.of(delivery("1-0", "d1")));

        verify(latest, times(2)).update(eq("d1"), anyLong(), any());
        verify(writer, times(1)).write(anyList());
        verify(bus, times(1)).ack(GROUP, List.of("1-0"));
        verify(bus, never()).deadLetter(anyString(), any(), anyString());
    }

    @Test
    void unexpectedFailureDeadLettersOnlyThatMessage() {
        when(identities.find("good")).thenReturn(Optional.of(identity("good")));
        when(identities.find("bad")).thenThrow(new IllegalStateException("boom"));
        Delivery a = delivery("1-0", "good");
        Delivery bad = delivery("2-0", "bad");
        Delivery c = delivery("3-0", "good");

        processor.process(List.of(a, bad, c));

        verify(bus).deadLetter(GROUP, bad, "PROCESSING_ERROR");
        verify(bus, times(1)).deadLetter(anyString(), any(), anyString());
        verify(writer, times(1)).write(anyList());
        verify(bus).ack(GROUP, List.of("1-0", "3-0"));
    }

    @Test
    void interruptDuringBackoffAbortsWithoutAck() {
        when(identities.find("d1")).thenReturn(Optional.of(identity("d1")));
        when(writer.write(anyList())).thenThrow(new TelemetryUnavailableException(new RuntimeException("down")));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> processor.process(List.of(delivery("1-0", "d1"))))
                    .isInstanceOf(PipelineProcessor.ProcessingAbortedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(bus, never()).ack(anyString(), anyList());
        verify(bus, never()).deadLetter(anyString(), any(), anyString());
    }

    @Test
    void permanentDataAccessFailureInLookupIsNotRetried() {
        when(identities.find("good")).thenReturn(Optional.of(identity("good")));
        when(identities.find("bad")).thenThrow(new BadSqlGrammarException("q", "select", new java.sql.SQLException("x")));
        Delivery bad = delivery("2-0", "bad");

        processor.process(List.of(delivery("1-0", "good"), bad, delivery("3-0", "good")));

        verify(identities, times(1)).find("bad");
        verify(bus).deadLetter(GROUP, bad, "PROCESSING_ERROR");
        verify(bus, times(1)).deadLetter(anyString(), any(), anyString());
        verify(bus).ack(GROUP, List.of("1-0", "3-0"));
    }

    @Test
    void permanentRedisFailureInLatestUpdateDeadLettersOnlyThatItem() {
        when(identities.find(anyString())).thenAnswer(i -> Optional.of(identity(i.getArgument(0))));
        doThrow(new RedisSystemException("WRONGTYPE", new RuntimeException("WRONGTYPE")))
                .when(latest).update(eq("bad"), anyLong(), any());
        Delivery bad = delivery("2-0", "bad");

        processor.process(List.of(delivery("1-0", "good"), bad, delivery("3-0", "good")));

        verify(latest, times(1)).update(eq("bad"), anyLong(), any());
        verify(bus).deadLetter(GROUP, bad, "PROCESSING_ERROR");
        verify(bus).ack(GROUP, List.of("1-0", "3-0"));
    }
}
