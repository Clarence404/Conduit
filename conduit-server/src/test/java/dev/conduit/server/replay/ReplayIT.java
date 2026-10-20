package dev.conduit.server.replay;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.support.IntegrationTestBase;
import dev.conduit.server.telemetry.InfluxClient;
import dev.conduit.server.telemetry.LatestValue;
import dev.conduit.server.telemetry.LatestValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static dev.conduit.server.support.InfluxTestSupport.awaitCount;
import static dev.conduit.server.support.InfluxTestSupport.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 遥测重放端到端集成测试：把总线中保留的原始报文重新写入时序库与最新值、不把离线设备标成在线、
 * 物模型全部测点被拒时只计数不写死信。
 * 测试时流水线未启用（application-test.yml 中 conduit.pipeline.enabled=false），所以写入总线的条目不会被消费掉。
 */
class ReplayIT extends IntegrationTestBase {

    private static final String PATH = "/api/v1/platform/telemetry/replay";

    @Autowired
    MessageBus bus;

    @Autowired
    InfluxClient influx;

    @Autowired
    LatestValueStore latest;

    @Autowired
    PresenceStore presence;

    TestDevice device;
    String sys;
    long windowStart;

    @BeforeEach
    void setUp() throws Exception {
        device = createDevice(defaultTenantId(), "ENABLED");
        sys = loginAsPlatformAdmin();
        windowStart = redisNow();
    }

    /**
     * Redis 服务器当前毫秒时间。总线条目 id 由 Redis 服务器的时钟生成，而 Redis 容器可能在远程 Docker 宿主机上，
     * 与测试进程时钟有偏差，所以测试的重放窗口按 Redis 的时间取，不能用本机时间。
     * 注意这是为了让测试确定，并<b>不是</b>操作员的真实路径：操作员在应用时钟上选窗口，而条目 id 来自 Redis 服务器时钟，
     * 窗口过紧时两端边缘都可能漏掉条目，这个偏差在本测试中被有意绕开了。
     */
    private long redisNow() {
        Long millis = redis.execute((RedisCallback<Long>) c -> c.serverCommands().time(TimeUnit.MILLISECONDS));
        return Objects.requireNonNull(millis, "redis time");
    }

    /** 把一条属性报文写进上行流；ts 为设备时间戳。 */
    private void publish(String payload, long receivedAt) {
        bus.publish(new RawMessage(device.productKey(), device.deviceKey(), MessageType.PROPERTY,
                payload.getBytes(StandardCharsets.UTF_8), receivedAt, Map.of()));
    }

    /**
     * 提交覆盖本测试已写入条目的重放（窗口为 [setUp 时刻, 现在+1)，测试类串行执行，窗口内只有本测试的条目），
     * 等任务结束后返回最终状态 JSON。
     */
    private String replayAndWait() throws Exception {
        long to = redisNow() + 1;
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":%d,\"to\":%d}".formatted(windowStart, to)))
                .andExpect(status().isOk());
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(this::currentStatus, body -> !"RUNNING".equals(JsonPath.read(body, "$.state")));
    }

    /** 查询当前任务状态的响应体。 */
    private String currentStatus() throws Exception {
        return mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(sys)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void replaysRawMessagesIntoInfluxAndLatestValues() throws Exception {
        Long deadBefore = redis.opsForStream().size("test:dead");
        long ts = windowStart - 60_000;
        publish("{\"ts\":%d,\"params\":{\"Ua\":220.5}}".formatted(ts), windowStart);
        publish("{\"ts\":%d,\"params\":{\"Ua\":221.5}}".formatted(ts + 1000), windowStart);

        String status = replayAndWait();

        assertThat((String) JsonPath.read(status, "$.state")).isEqualTo("SUCCEEDED");
        assertThat((int) JsonPath.read(status, "$.scanned")).isEqualTo(2);
        assertThat((int) JsonPath.read(status, "$.written")).isEqualTo(2);
        assertThat((int) JsonPath.read(status, "$.skipped")).isZero();
        assertThat((int) JsonPath.read(status, "$.rejected")).isZero();
        awaitCount(influx, device.deviceKey(), "Ua", 2);
        assertThat(latest.get(device.deviceKey()))
                .containsEntry("Ua", new LatestValue(221.5, ts + 1000));
        assertThat(redis.opsForStream().size("test:dead")).isEqualTo(deadBefore);

        // 幂等：同一窗口再重放一次，同设备同时间戳的写入覆盖为同一个值，计数与最新值都不变（spec 3 的安全前提）。
        String again = replayAndWait();
        assertThat((String) JsonPath.read(again, "$.state")).isEqualTo("SUCCEEDED");
        assertThat((int) JsonPath.read(again, "$.scanned")).isEqualTo(2);
        assertThat(count(influx, device.deviceKey(), "Ua")).isEqualTo(2);
        assertThat(latest.get(device.deviceKey()))
                .containsEntry("Ua", new LatestValue(221.5, ts + 1000));
    }

    @Test
    void doesNotBringAnOfflineDeviceBackOnline() throws Exception {
        long offlineSince = windowStart - 600_000;
        presence.apply(device.deviceKey(), false, offlineSince, "10.1.2.3");
        publish("{\"ts\":%d,\"params\":{\"Ua\":220.5}}".formatted(windowStart - 60_000), windowStart);

        String status = replayAndWait();

        assertThat((String) JsonPath.read(status, "$.state")).isEqualTo("SUCCEEDED");
        assertThat((int) JsonPath.read(status, "$.written")).isEqualTo(1);
        Optional<Presence> after = presence.get(device.deviceKey());
        assertThat(after).contains(new Presence(false, offlineSince, "10.1.2.3"));
    }

    @Test
    void countsFullyRejectedMessagesWithoutWritingDeadLetters() throws Exception {
        defineProperty(device.productKey(), "Ua", "DOUBLE", null, null);
        Long deadBefore = redis.opsForStream().size("test:dead");
        publish("{\"ts\":%d,\"params\":{\"Ua\":\"abc\"}}".formatted(windowStart - 60_000), windowStart);

        String status = replayAndWait();

        assertThat((String) JsonPath.read(status, "$.state")).isEqualTo("SUCCEEDED");
        assertThat((int) JsonPath.read(status, "$.rejected")).isEqualTo(1);
        assertThat((int) JsonPath.read(status, "$.written")).isZero();
        assertThat(redis.opsForStream().size("test:dead")).isEqualTo(deadBefore);
    }
}
