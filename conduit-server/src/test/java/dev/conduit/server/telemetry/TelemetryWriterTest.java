package dev.conduit.server.telemetry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 统一用 doReturn/doThrow 打桩：对已打桩为抛异常的方法再用 when(...) 会在打桩时就抛出。 */
class TelemetryWriterTest {

    InfluxClient client = mock(InfluxClient.class);
    TelemetryWriter writer;

    @BeforeEach
    void setUp() {
        writer = new TelemetryWriter(client, new InfluxProperties("http://x", "apiv3_t", "telemetry", "30d"));
        doReturn(List.of()).when(client).writeLines(anyString(), anyList());
    }

    private static TelemetryPoint p(Map<String, Object> fields) {
        return new TelemetryPoint(1, "pk", "dk", 1L, fields);
    }

    @SuppressWarnings("unchecked")
    private List<String> writtenLines() {
        ArgumentCaptor<List<String>> cap = ArgumentCaptor.forClass(List.class);
        verify(client).writeLines(eq("telemetry"), cap.capture());
        return cap.getValue();
    }

    @Test
    void writesTypedLinesWithTags() {
        WriteResult r = writer.write(List.of(p(Map.of("n", 5L))));

        assertThat(r.rejected()).isEmpty();
        assertThat(writtenLines()).containsExactly("property,tenant_id=1,product_key=pk,device_key=dk n=5i 1");
    }

    @Test
    void unavailableResponsesThrowRecoverable() {
        for (int status : new int[]{500, 503, 401, 403, 404, 413}) {
            doThrow(new InfluxHttpException(status, "x")).when(client).writeLines(anyString(), anyList());
            assertThatThrownBy(() -> writer.write(List.of(p(Map.of("a", 1.0)))))
                    .as("status %d", status)
                    .isInstanceOf(TelemetryUnavailableException.class);
        }
        doThrow(new UncheckedIOException(new IOException("down"))).when(client).writeLines(anyString(), anyList());
        assertThatThrownBy(() -> writer.write(List.of(p(Map.of("a", 1.0)))))
                .isInstanceOf(TelemetryUnavailableException.class);
    }

    @Test
    void non400PartialBodyIsNotRecoverableAndNotADataRejection() {
        doThrow(new InfluxHttpException(400, "bad request")).when(client).writeLines(anyString(), anyList());

        assertThatThrownBy(() -> writer.write(List.of(p(Map.of("a", 1.0)))))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TelemetryUnavailableException.class);
    }

    @Test
    void partialWriteMapsOneBasedLinesToOriginalIndexes() {
        doReturn(List.of(new InfluxClient.LineError(2, "invalid column type for column 'a'")))
                .when(client).writeLines(anyString(), anyList());

        WriteResult r = writer.write(List.of(
                p(Map.of("a", List.of(1))),  // 0: conversion failure, not sent
                p(Map.of("a", 2.0)),         // 1: line 1
                p(Map.of("a", "x")),         // 2: line 2 -> rejected by InfluxDB
                p(Map.of("a", 4.0))));       // 3: line 3

        assertThat(r.rejected()).extracting(WriteResult.Rejected::index).containsExactly(0, 2);
        assertThat(r.rejected().get(1).message()).startsWith("invalid column type");
        assertThat(writtenLines()).hasSize(3);
    }

    @Test
    void unconvertiblePointsAreRejectedAtOriginalIndexAndRestIsWritten() {
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("a", null);

        WriteResult r = writer.write(List.of(
                p(Map.of("a", 1.0)),
                p(Map.of("a", List.of(1))),
                p(nullValue),
                p(null),
                p(Map.of("a", 7)),           // Integer is not a supported field type
                p(Map.of("a", 5.0))));

        assertThat(r.rejected()).extracting(WriteResult.Rejected::index).containsExactly(1, 2, 3, 4);
        assertThat(writtenLines()).hasSize(2);
    }

    @Test
    void allUnconvertibleSkipsWrite() {
        WriteResult r = writer.write(List.of(p(Map.of("a", List.of()))));

        assertThat(r.rejected()).hasSize(1);
        verify(client, never()).writeLines(anyString(), anyList());
    }

    @Test
    void lineNumberOutsideBatchFails() {
        doReturn(List.of(new InfluxClient.LineError(5, "x"))).when(client).writeLines(anyString(), anyList());

        assertThatThrownBy(() -> writer.write(List.of(p(Map.of("a", 1.0)))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyBatchIsNoop() {
        assertThat(writer.write(List.of()).rejected()).isEmpty();
        verify(client, never()).writeLines(anyString(), anyList());
    }
}
