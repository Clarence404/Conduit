package dev.conduit.e2e;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult;
import dev.conduit.simulator.DeviceCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 测试代码扮演的一台真实设备：MQTT 5、不用自动重连，QoS1 发布后等待 PUBACK；异常、超时或 PUBACK 错误码时
 * 重新连接并重发同一载荷（同一 ts，平台按设备 + 时间戳覆盖，不会多计），直到成功，最长 5 分钟。
 */
final class E2eDevice implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(E2eDevice.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);
    private static final Duration GIVE_UP_AFTER = Duration.ofMinutes(5);

    private final String host;
    private final int port;
    private final DeviceCredentials credentials;
    private Mqtt5AsyncClient client;

    E2eDevice(String host, int port, DeviceCredentials credentials) {
        this.host = host;
        this.port = port;
        this.credentials = credentials;
    }

    String deviceKey() {
        return credentials.deviceKey();
    }

    String productKey() {
        return credentials.productKey();
    }

    /**
     * 发布一条 QoS1 消息直到收到成功的 PUBACK。
     *
     * @throws IllegalStateException 5 分钟内仍未成功
     */
    void publishUntilAcked(String topic, byte[] payload) throws InterruptedException {
        long deadline = System.nanoTime() + GIVE_UP_AFTER.toNanos();
        while (true) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Device " + deviceKey() + " could not publish within " + GIVE_UP_AFTER);
            }
            try {
                ensureConnected();
                Mqtt5PublishResult result = client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE)
                        .payload(payload).send().get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                String problem = problemOf(result);
                if (problem == null) {
                    return;
                }
                log.debug("Device {} publish rejected ({}), reconnecting", deviceKey(), problem);
                dropConnection();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.debug("Device {} publish failed ({}), retrying", deviceKey(), describe(e));
                if (client == null || !client.getState().isConnected()) {
                    dropConnection();
                }
            }
            Thread.sleep(RETRY_DELAY.toMillis());
        }
    }

    /** 未连接时新建客户端并连接（deviceKey 为 clientId 与用户名，密钥为密码）；失败抛出，由调用方重试。 */
    private void ensureConnected() throws InterruptedException, ExecutionException, TimeoutException {
        if (client != null && client.getState().isConnected()) {
            return;
        }
        dropConnection();
        client = MqttClient.builder().useMqttVersion5()
                .identifier(deviceKey())
                .serverHost(host).serverPort(port)
                .simpleAuth().username(deviceKey()).password(credentials.secret().getBytes(StandardCharsets.UTF_8))
                .applySimpleAuth()
                .buildAsync();
        client.connect().get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 断开并丢弃当前客户端（忽略断开失败）。 */
    private void dropConnection() {
        Mqtt5AsyncClient c = client;
        client = null;
        if (c != null) {
            try {
                c.disconnect().get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.trace("Disconnect of {} failed: {}", deviceKey(), describe(e));
            }
        }
    }

    @Override
    public void close() {
        dropConnection();
    }

    /** 发布结果中的失败原因；成功返回 null。 */
    private static String problemOf(Mqtt5PublishResult result) {
        if (result.getError().isPresent()) {
            return String.valueOf(result.getError().get().getMessage());
        }
        if (result instanceof Mqtt5PublishResult.Mqtt5Qos1Result qos1 && qos1.getPubAck().getReasonCode().isError()) {
            return "PUBACK " + qos1.getPubAck().getReasonCode();
        }
        return null;
    }

    private static String describe(Throwable e) {
        Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
