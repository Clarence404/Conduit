package dev.conduit.server.replay;

import dev.conduit.common.bus.Delivery;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.common.message.RawMessageCodec;
import dev.conduit.server.pipeline.Backoff;
import dev.conduit.server.pipeline.DeadReason;
import dev.conduit.server.pipeline.DecodeException;
import dev.conduit.server.pipeline.IngestSource;
import dev.conduit.server.pipeline.TelemetryIngestor;
import dev.conduit.server.telemetry.TelemetryPoint;
import dev.conduit.server.telemetry.TelemetryUnavailableException;
import dev.conduit.server.telemetry.WriteResult;
import dev.conduit.server.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReplayService 单元测试：id 区间与分批、四项计数、取消、同时只允许一个任务（409）、
 * 重试超限后 FAILED，以及「不写死信、不 ACK」。
 */
class ReplayServiceTest {

    private static final long FROM = 1760400000000L;
    private static final long TO = FROM + 1000;
    private static final long NOW = 1760400009999L;

    /** 收下 Runnable 但不执行，由测试自己决定何时跑，使取消与 409 路径可确定地断言。 */
    private static final class PendingExecutor implements Executor {
        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        void runAll() {
            List<Runnable> copy = List.copyOf(pending);
            pending.clear();
            copy.forEach(Runnable::run);
        }
    }

    MessageBus bus = mock(MessageBus.class);
    TelemetryIngestor ingestor = mock(TelemetryIngestor.class);
    Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    Backoff backoff = new Backoff(Duration.ofMillis(1), Duration.ofMillis(2), 3);

    @BeforeEach
    void setUp() {
        when(ingestor.write(anyList())).thenReturn(WriteResult.ok());
        when(bus.range(anyString(), anyString(), anyInt())).thenReturn(List.of());
    }

    /** 同步执行的服务：start 返回时任务已经跑完。 */
    private ReplayService syncService() {
        return new ReplayService(bus, ingestor, clock, backoff, Runnable::run);
    }

    private static Delivery property(String id, String deviceKey) {
        RawMessage m = new RawMessage("pk", deviceKey, MessageType.PROPERTY,
                "{}".getBytes(StandardCharsets.UTF_8), FROM, Map.of());
        return new Delivery(id, RawMessageCodec.toFields(m), 0);
    }

    private static Delivery presence(String id, String deviceKey) {
        RawMessage m = new RawMessage("pk", deviceKey, MessageType.PRESENCE_CONNECTED,
                new byte[0], FROM, Map.of());
        return new Delivery(id, RawMessageCodec.toFields(m), 0);
    }

    private static Delivery broken(String id) {
        return new Delivery(id, Map.of("dk", "d1"), 0);
    }

    private static TelemetryPoint point(String deviceKey) {
        return new TelemetryPoint(1L, "pk", deviceKey, FROM, Map.of("Ua", 220.5));
    }

    private void prepares(String deviceKey) {
        when(ingestor.prepare(anyString(), any(), any())).thenReturn(
                new TelemetryIngestor.Prepared(point(deviceKey), List.of()));
    }

    /** 第一批返回 batch，之后返回空，模拟读完整个范围。 */
    private void readsOneBatch(List<Delivery> batch) {
        when(bus.range(anyString(), anyString(), anyInt())).thenReturn(batch).thenReturn(List.of());
    }

    @Test
    void statusIsNoneBeforeAnyTask() {
        assertThat(syncService().status()).isEqualTo(ReplayStatus.none());
    }

    @Test
    void readsTheHalfOpenRangeAsAnInclusiveIdRange() {
        readsOneBatch(List.of(property("1760400000000-0", "d1")));
        prepares("d1");

        syncService().start(new ReplayRequest(FROM, TO));

        verify(bus).range("1760400000000-0", "1760400000999-18446744073709551615", 500);
    }

    @Test
    void preparesEveryMessageWithTheReplaySource() {
        readsOneBatch(List.of(property("1760400000000-0", "d1")));
        prepares("d1");

        syncService().start(new ReplayRequest(FROM, TO));

        verify(ingestor).prepare(anyString(), any(), eq(IngestSource.REPLAY));
    }

    @Test
    void continuesAfterTheLastEntryOfEachBatch() {
        when(bus.range(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(property("1760400000000-7", "d1")))
                .thenReturn(List.of());
        prepares("d1");

        syncService().start(new ReplayRequest(FROM, TO));

        verify(bus).range("1760400000000-8", "1760400000999-18446744073709551615", 500);
    }

    @Test
    void startReturnsTheSnapshotTakenAtSubmission() {
        readsOneBatch(List.of(property("1760400000000-0", "d1")));
        prepares("d1");

        ReplayStatus status = new ReplayService(bus, ingestor, clock, backoff, new PendingExecutor())
                .start(new ReplayRequest(FROM, TO));

        assertThat(status.state()).isEqualTo(ReplayState.RUNNING);
        assertThat(status.from()).isEqualTo(FROM);
        assertThat(status.to()).isEqualTo(TO);
        assertThat(status.startedAt()).isEqualTo(NOW);
        assertThat(status.finishedAt()).isNull();
        assertThat(status.scanned()).isZero();
    }

    @Test
    void countsScannedWrittenSkippedAndRejected() {
        readsOneBatch(List.of(
                property("1760400000000-0", "d1"),
                presence("1760400000001-0", "d1"),
                broken("1760400000002-0"),
                property("1760400000003-0", "d2")));
        when(ingestor.prepare(anyString(), any(), any()))
                .thenReturn(new TelemetryIngestor.Prepared(point("d1"), List.of()))
                .thenThrow(new DecodeException(DeadReason.FIELD_REJECTED, "all fields rejected"));

        ReplayService service = syncService();
        service.start(new ReplayRequest(FROM, TO));
        ReplayStatus status = service.status();

        assertThat(status.state()).isEqualTo(ReplayState.SUCCEEDED);
        assertThat(status.scanned()).isEqualTo(4);
        assertThat(status.written()).isEqualTo(1);
        assertThat(status.skipped()).isEqualTo(2);
        assertThat(status.rejected()).isEqualTo(1);
        assertThat(status.from()).isEqualTo(FROM);
        assertThat(status.to()).isEqualTo(TO);
        assertThat(status.startedAt()).isEqualTo(NOW);
        assertThat(status.finishedAt()).isEqualTo(NOW);
        assertThat(status.error()).isNull();
    }

    @Test
    void countsADecodeFailureThatIsNotAFieldRejectionAsSkipped() {
        readsOneBatch(List.of(property("1760400000000-0", "ghost")));
        when(ingestor.prepare(anyString(), any(), any()))
                .thenThrow(new DecodeException(DeadReason.UNKNOWN_DEVICE, "ghost"));

        ReplayService service = syncService();
        service.start(new ReplayRequest(FROM, TO));

        assertThat(service.status().skipped()).isEqualTo(1);
        assertThat(service.status().rejected()).isZero();
    }

    @Test
    void countsPointsThatInfluxRejectsAsSkipped() {
        readsOneBatch(List.of(property("1760400000000-0", "d1"), property("1760400000001-0", "d1")));
        prepares("d1");
        when(ingestor.write(anyList()))
                .thenReturn(new WriteResult(List.of(new WriteResult.Rejected(1, "type conflict"))));

        ReplayService service = syncService();
        service.start(new ReplayRequest(FROM, TO));

        assertThat(service.status().written()).isEqualTo(1);
        assertThat(service.status().skipped()).isEqualTo(1);
    }

    @Test
    void neverWritesDeadLettersAndNeverAcks() {
        readsOneBatch(List.of(property("1760400000000-0", "ghost")));
        when(ingestor.prepare(anyString(), any(), any()))
                .thenThrow(new DecodeException(DeadReason.FIELD_REJECTED, "all fields rejected"));

        syncService().start(new ReplayRequest(FROM, TO));

        verify(bus, never()).recordDead(any(), anyString(), any());
        verify(bus, never()).deadLetter(anyString(), any(), anyString());
        verify(bus, never()).ack(anyString(), anyList());
    }

    @Test
    void refusesASecondTaskWhileOneIsRunning() {
        PendingExecutor executor = new PendingExecutor();
        ReplayService service = new ReplayService(bus, ingestor, clock, backoff, executor);
        service.start(new ReplayRequest(FROM, TO));

        assertThatThrownBy(() -> service.start(new ReplayRequest(FROM, TO)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void acceptsANewTaskOnceThePreviousOneHasFinished() {
        readsOneBatch(List.of());
        ReplayService service = syncService();
        service.start(new ReplayRequest(FROM, TO));

        assertThat(service.start(new ReplayRequest(FROM, TO)).state()).isEqualTo(ReplayState.RUNNING);
    }

    @Test
    void cancellingStopsTheTaskAndKeepsTheStatisticsSoFar() {
        PendingExecutor executor = new PendingExecutor();
        ReplayService service = new ReplayService(bus, ingestor, clock, backoff, executor);
        service.start(new ReplayRequest(FROM, TO));

        service.cancel();
        executor.runAll();

        ReplayStatus status = service.status();
        assertThat(status.state()).isEqualTo(ReplayState.CANCELLED);
        assertThat(status.scanned()).isZero();
        assertThat(status.finishedAt()).isEqualTo(NOW);
        verify(bus, never()).range(anyString(), anyString(), anyInt());
    }

    @Test
    void cancellingWithoutARunningTaskIsAConflict() {
        assertThatThrownBy(() -> syncService().cancel())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void failsWithAReasonWhenTheWriteKeepsFailing() {
        readsOneBatch(List.of(property("1760400000000-0", "d1")));
        prepares("d1");
        when(ingestor.write(anyList()))
                .thenThrow(new TelemetryUnavailableException(new RuntimeException("influx down")));

        ReplayService service = syncService();
        service.start(new ReplayRequest(FROM, TO));

        ReplayStatus status = service.status();
        assertThat(status.state()).isEqualTo(ReplayState.FAILED);
        assertThat(status.error()).contains("influx down");
        assertThat(status.finishedAt()).isEqualTo(NOW);
    }
}
