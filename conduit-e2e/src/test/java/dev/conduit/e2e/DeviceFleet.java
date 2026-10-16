package dev.conduit.e2e;

import dev.conduit.simulator.DeviceCredentials;
import dev.conduit.simulator.PayloadFormat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;

/**
 * 一个场景的全部设备：每台设备一个线程，按固定间隔发送 messagesPerDevice 条标准 JSON 上报（测点 Ua，ts 严格递增），
 * 每条都等到 PUBACK 才算发出；记录已确认的条数，供场景在发送中途注入故障。
 */
final class DeviceFleet {

    private final List<E2eDevice> devices;
    private final int messagesPerDevice;
    private final Duration interval;
    private final AtomicInteger acked = new AtomicInteger();
    private volatile boolean stopped;

    DeviceFleet(String host, int port, List<DeviceCredentials> credentials, int messagesPerDevice, Duration interval) {
        this.devices = credentials.stream().map(c -> new E2eDevice(host, port, c)).toList();
        this.messagesPerDevice = messagesPerDevice;
        this.interval = interval;
    }

    /** 启动全部设备；返回的 future 在所有设备发完时完成，任一设备放弃时以其异常完成。 */
    CompletableFuture<Void> start() {
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        for (E2eDevice device : devices) {
            CompletableFuture<Void> run = new CompletableFuture<>();
            Thread.ofPlatform().name("e2e-" + device.deviceKey()).daemon().start(() -> {
                try {
                    send(device);
                    run.complete(null);
                } catch (Throwable t) {
                    run.completeExceptionally(t);
                } finally {
                    device.close();
                }
            });
            runs.add(run);
        }
        return CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new));
    }

    /** 一台设备按计划时间发完全部消息；故障期间落后的消息在恢复后连续补发。 */
    private void send(E2eDevice device) throws InterruptedException {
        String topic = "conduit/%s/%s/up/property".formatted(device.productKey(), device.deviceKey());
        long lastTs = 0;
        long next = System.currentTimeMillis();
        for (int seq = 1; seq <= messagesPerDevice; seq++) {
            long ts = Math.max(System.currentTimeMillis(), lastTs + 1);
            lastTs = ts;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("Ua", 220 + seq / 100.0);
            device.publishUntilAcked(topic, PayloadFormat.STANDARD.encode(device.deviceKey() + "-" + seq, ts, params),
                    () -> stopped);
            if (stopped) {
                return;
            }
            acked.incrementAndGet();
            next += interval.toMillis();
            long wait = next - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
        }
    }

    /** 让所有设备线程尽快退出发送循环并关闭连接（场景失败或结束时调用，幂等）。 */
    void stop() {
        stopped = true;
        devices.forEach(E2eDevice::close);
    }

    int acked() {
        return acked.get();
    }

    long total() {
        return (long) devices.size() * messagesPerDevice;
    }

    /** 等到至少 count 条已确认。 */
    void awaitAcked(int count, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(200)).until(() -> acked.get() >= count);
    }
}
