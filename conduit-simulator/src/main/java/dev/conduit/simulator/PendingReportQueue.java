package dev.conduit.simulator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单台模拟设备的内存待确认队列。消息只有收到业务回执才删除；到期领取会原子推进发送时间，防止并发重复重发。
 * 真实设备应把等价状态持久化到本地存储。
 */
final class PendingReportQueue {

    private final int capacity;
    private final Map<String, PendingReport> reports = new LinkedHashMap<>();

    PendingReportQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
    }

    /** 队列有空间且 id 不重复时加入；payload 与时间戳随后用于原样重发。 */
    synchronized boolean offer(String id, String topic, byte[] payload, long sentAt) {
        if (reports.size() >= capacity || reports.containsKey(id)) {
            return false;
        }
        reports.put(id, new PendingReport(id, topic, payload, sentAt));
        return true;
    }

    /** 按 id 确认并删除；未知或重复回执返回 false。 */
    synchronized boolean acknowledge(String id) {
        return reports.remove(id) != null;
    }

    /** 领取所有超时消息用于重发，同时推进其最后发送时间。 */
    synchronized List<PendingReport> claimExpired(long now, Duration timeout) {
        List<PendingReport> expired = new ArrayList<>();
        for (Map.Entry<String, PendingReport> entry : reports.entrySet()) {
            PendingReport report = entry.getValue();
            if (now - report.sentAt() >= timeout.toMillis() || now < report.sentAt()) {
                PendingReport claimed = new PendingReport(report.id(), report.topic(), report.payload(), now);
                entry.setValue(claimed);
                expired.add(claimed);
            }
        }
        return expired;
    }

    synchronized boolean hasCapacity() {
        return reports.size() < capacity;
    }

    synchronized int size() {
        return reports.size();
    }

    /** 待确认上报；payload 在队列生命周期内只读，确保重发内容（尤其 id 与 ts）完全不变。 */
    record PendingReport(String id, String topic, byte[] payload, long sentAt) {
    }
}
