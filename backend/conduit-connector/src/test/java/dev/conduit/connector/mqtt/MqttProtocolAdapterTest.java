package dev.conduit.connector.mqtt;

import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import dev.conduit.common.bus.MessageBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqttProtocolAdapterTest {

    MessageBus bus;
    Mqtt5Publish publish;
    MqttProtocolAdapter adapter;

    @BeforeEach
    void setUp() {
        bus = mock(MessageBus.class);
        publish = mock(Mqtt5Publish.class);
        when(publish.getTopic()).thenReturn(MqttTopic.of("conduit/pk1/dk1/up/property"));
        when(publish.getPayloadAsBytes()).thenReturn(new byte[]{1});
        var props = new MqttConnectorProperties("localhost", 1883, "conduit-connector-ut",
                null, null, "conduit", 300, "conduit-");
        adapter = new MqttProtocolAdapter(props, bus,
                Clock.fixed(Instant.ofEpochMilli(1), ZoneOffset.UTC), Duration.ofMillis(1));
    }

    @Test
    void acksOnlyAfterSuccessfulPublishAndRetriesInline() {
        when(bus.publish(any()))
                .thenThrow(new IllegalStateException("redis down"))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn("1-0");

        adapter.onPublish(publish);

        InOrder order = inOrder(bus, publish);
        order.verify(bus, times(3)).publish(any());
        order.verify(publish, times(1)).acknowledge();
        verify(bus, times(3)).publish(any());
    }

    @Test
    void doesNotAckWhilePublishKeepsFailingThenGivesUpWhenStopped() throws Exception {
        when(bus.publish(any())).thenThrow(new IllegalStateException("redis down"));
        Thread t = Thread.startVirtualThread(() -> adapter.onPublish(publish));
        Thread.sleep(50);
        adapter.stop();
        t.interrupt();
        t.join(2000);

        verify(publish, never()).acknowledge();
    }

    @Test
    void ackFailureDoesNotRepublish() {
        when(bus.publish(any())).thenReturn("1-0");
        doThrow(new IllegalStateException("connection lost")).when(publish).acknowledge();

        adapter.onPublish(publish);

        verify(bus, times(1)).publish(any());
        verify(publish, times(1)).acknowledge();
    }
}
