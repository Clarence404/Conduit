package dev.conduit.connector.mqtt;

import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientConnectedContext;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import dev.conduit.common.bus.MessageBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
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

        assertThat(t.isAlive()).isFalse();
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

    @Test
    void subscribeIsRetriedWhileConnectedAndNeverDisconnects() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        Supplier<CompletableFuture<?>> subscriber = () -> {
            if (calls.incrementAndGet() < 3) {
                return CompletableFuture.failedFuture(new IllegalStateException("suback fail"));
            }
            done.countDown();
            return CompletableFuture.completedFuture(null);
        };

        adapter.subscribeWithRetry(subscriber, () -> true, 0);

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(3);
        adapter.stop();
    }

    @Test
    void subscribeRetryStopsWhenDisconnected() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        adapter.subscribeWithRetry(() -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("fail"));
        }, () -> false, 0);
        Thread.sleep(100);
        assertThat(calls.get()).isEqualTo(1);
        adapter.stop();
    }

    @Test
    void everyConnackSubscribesEvenWhenSessionPresentAndSignalCompletesOnlyOnSuback() {
        AtomicInteger calls = new AtomicInteger();
        adapter.subscriber = () -> calls.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("dropped before suback"))
                : CompletableFuture.completedFuture(null);
        adapter.connected = () -> false; // first attempt fails and is not retried (connection lost)

        adapter.onConnected(connAckContext(false));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(adapter.initialSubscription).isNotDone();

        // auto-reconnect: broker kept the session (sessionPresent=true) but it has no subscriptions
        adapter.onConnected(connAckContext(true));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(adapter.initialSubscription).isCompleted();
        adapter.stop();
    }

    private static Mqtt5ClientConnectedContext connAckContext(boolean sessionPresent) {
        Mqtt5ConnAck connAck = mock(Mqtt5ConnAck.class);
        when(connAck.isSessionPresent()).thenReturn(sessionPresent);
        Mqtt5ClientConnectedContext ctx = mock(Mqtt5ClientConnectedContext.class);
        when(ctx.getConnAck()).thenReturn(connAck);
        return ctx;
    }
}
