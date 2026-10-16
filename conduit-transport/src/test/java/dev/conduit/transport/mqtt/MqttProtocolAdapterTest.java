package dev.conduit.transport.mqtt;

import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource;
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5DisconnectException;
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5Disconnect;
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAck;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAckReasonCode;
import dev.conduit.common.bus.MessageBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MqttProtocolAdapter 单元测试：写入总线成功后才 ACK、停止时放弃重试、自有客户端上下线直接 ACK、
 * 意外断线与会话被接管时请求重启、SUBACK 含错误码视为订阅失败、连不上 broker 时 start 失败且不留状态。
 */
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
        var props = new MqttTransportProperties("localhost", 1883, "conduit-transport-ut",
                null, null, 86400, "conduit-");
        adapter = new MqttProtocolAdapter(props, bus,
                Clock.fixed(Instant.ofEpochMilli(1), ZoneOffset.UTC), Duration.ofMillis(1));
    }

    @AfterEach
    void tearDown() {
        adapter.stop();
    }

    @Test
    void acksOnlyAfterSuccessfulPublishAndRetriesInline() {
        when(bus.publish(any()))
                .thenThrow(new IllegalStateException("redis down"))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn("1-0");

        adapter.onPublish(adapter.openSession(), publish);

        InOrder order = inOrder(bus, publish);
        order.verify(bus, times(3)).publish(any());
        order.verify(publish, times(1)).acknowledge();
    }

    @Test
    void doesNotAckWhilePublishKeepsFailingThenGivesUpWhenStopped() throws Exception {
        when(bus.publish(any())).thenThrow(new IllegalStateException("redis down"));
        MqttProtocolAdapter.Session session = adapter.openSession();
        Thread t = Thread.startVirtualThread(() -> adapter.onPublish(session, publish));
        Thread.sleep(50);

        adapter.stop();
        t.join(2000);

        assertThat(t.isAlive()).isFalse();
        verify(publish, never()).acknowledge();
    }

    @Test
    void ackFailureDoesNotRepublish() {
        when(bus.publish(any())).thenReturn("1-0");
        doThrow(new IllegalStateException("connection lost")).when(publish).acknowledge();

        adapter.onPublish(adapter.openSession(), publish);

        verify(bus, times(1)).publish(any());
        verify(publish, times(1)).acknowledge();
    }

    @Test
    void presenceOfThePlatformsOwnClientIsAcknowledgedWithoutPublishing() {
        when(publish.getTopic())
                .thenReturn(MqttTopic.of("$SYS/brokers/emqx@127.0.0.1/clients/conduit-transport/connected"));

        adapter.onPublish(adapter.openSession(), publish);

        verify(bus, never()).publish(any());
        verify(publish).acknowledge();
    }

    @Test
    void anUnexpectedDisconnectRequestsARestart() {
        MqttProtocolAdapter.Session session = adapter.openSession();

        adapter.onDisconnected(session, disconnected(MqttDisconnectSource.SERVER,
                new IllegalStateException("broker restarted")));

        assertThat(adapter.needsRestart()).isTrue();
        assertThat(adapter.isConnected()).isFalse();
    }

    @Test
    void aDisconnectByTheAdapterItselfDoesNotRequestARestart() {
        MqttProtocolAdapter.Session session = adapter.openSession();

        adapter.onDisconnected(session, disconnected(MqttDisconnectSource.USER, null));

        assertThat(adapter.needsRestart()).isFalse();
    }

    @Test
    void stopClearsTheRestartRequest() {
        MqttProtocolAdapter.Session session = adapter.openSession();
        adapter.onDisconnected(session, disconnected(MqttDisconnectSource.CLIENT, null));

        adapter.stop();

        assertThat(adapter.needsRestart()).isFalse();
        assertThat(adapter.isConnected()).isFalse();
    }

    @Test
    void recognisesASessionTakenOverByAnotherConnection() {
        Mqtt5Disconnect takenOver = mock(Mqtt5Disconnect.class);
        when(takenOver.getReasonCode()).thenReturn(Mqtt5DisconnectReasonCode.SESSION_TAKEN_OVER);
        Mqtt5Disconnect shutdown = mock(Mqtt5Disconnect.class);
        when(shutdown.getReasonCode()).thenReturn(Mqtt5DisconnectReasonCode.SERVER_SHUTTING_DOWN);

        assertThat(MqttProtocolAdapter.isSessionTakenOver(new Mqtt5DisconnectException(takenOver, "taken over")))
                .isTrue();
        assertThat(MqttProtocolAdapter.isSessionTakenOver(new Mqtt5DisconnectException(shutdown, "shutdown")))
                .isFalse();
        assertThat(MqttProtocolAdapter.isSessionTakenOver(new IllegalStateException("other"))).isFalse();
        assertThat(MqttProtocolAdapter.isSessionTakenOver(null)).isFalse();
    }

    @Test
    void aSubAckWithAnyErrorCodeCountsAsAFailedSubscription() {
        Mqtt5SubAck partial = mock(Mqtt5SubAck.class);
        when(partial.getReasonCodes()).thenReturn(List.of(Mqtt5SubAckReasonCode.GRANTED_QOS_1,
                Mqtt5SubAckReasonCode.NOT_AUTHORIZED, Mqtt5SubAckReasonCode.GRANTED_QOS_1));
        Mqtt5SubAck granted = mock(Mqtt5SubAck.class);
        when(granted.getReasonCodes()).thenReturn(List.of(Mqtt5SubAckReasonCode.GRANTED_QOS_1,
                Mqtt5SubAckReasonCode.GRANTED_QOS_1, Mqtt5SubAckReasonCode.GRANTED_QOS_1));

        assertThatThrownBy(() -> MqttProtocolAdapter.requireGranted(partial))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_AUTHORIZED");
        MqttProtocolAdapter.requireGranted(granted);
    }

    @Test
    void startFailsCleanlyWhenTheBrokerIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var props = new MqttTransportProperties("127.0.0.1", closedPort, "conduit-transport-ut",
                null, null, 86400, "conduit-");
        MqttProtocolAdapter unreachable = new MqttProtocolAdapter(props, bus, Clock.systemUTC());

        assertThatThrownBy(unreachable::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("127.0.0.1:" + closedPort);
        assertThat(unreachable.isConnected()).isFalse();
        assertThat(unreachable.needsRestart()).isFalse();
    }

    private static MqttClientDisconnectedContext disconnected(MqttDisconnectSource source, Throwable cause) {
        MqttClientDisconnectedContext context = mock(MqttClientDisconnectedContext.class);
        when(context.getSource()).thenReturn(source);
        when(context.getCause()).thenReturn(cause);
        return context;
    }
}
