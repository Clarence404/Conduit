package dev.conduit.simulator;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 待确认队列单元测试：按 id 出队、原样超时重发、乱序回执与容量背压。 */
class PendingReportQueueTest {

    @Test
    void removesOnlyTheAcknowledgedIdEvenWhenAcksArriveOutOfOrder() {
        PendingReportQueue queue = new PendingReportQueue(3);
        queue.offer("d-1", "up", new byte[]{1}, 100);
        queue.offer("d-2", "up", new byte[]{2}, 100);

        assertThat(queue.acknowledge("d-2")).isTrue();
        assertThat(queue.acknowledge("unknown")).isFalse();
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.acknowledge("d-1")).isTrue();
        assertThat(queue.size()).isZero();
    }

    @Test
    void claimsExpiredReportWithTheSameIdTopicAndPayload() {
        PendingReportQueue queue = new PendingReportQueue(2);
        byte[] payload = new byte[]{4, 2};
        queue.offer("d-42", "topic", payload, 100);

        assertThat(queue.claimExpired(109, Duration.ofMillis(10))).isEmpty();
        assertThat(queue.claimExpired(110, Duration.ofMillis(10))).singleElement().satisfies(report -> {
            assertThat(report.id()).isEqualTo("d-42");
            assertThat(report.topic()).isEqualTo("topic");
            assertThat(report.payload()).isSameAs(payload);
        });
        assertThat(queue.claimExpired(119, Duration.ofMillis(10))).isEmpty();
    }

    @Test
    void refusesNewReportsAtCapacityUntilOneIsAcknowledged() {
        PendingReportQueue queue = new PendingReportQueue(1);

        assertThat(queue.offer("d-1", "up", new byte[]{1}, 1)).isTrue();
        assertThat(queue.hasCapacity()).isFalse();
        assertThat(queue.offer("d-2", "up", new byte[]{2}, 2)).isFalse();
        queue.acknowledge("d-1");
        assertThat(queue.offer("d-2", "up", new byte[]{2}, 2)).isTrue();
    }
}
