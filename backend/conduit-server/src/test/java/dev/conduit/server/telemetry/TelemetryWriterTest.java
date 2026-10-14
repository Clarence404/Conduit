package dev.conduit.server.telemetry;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import com.influxdb.exceptions.InfluxException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryWriterTest {

    InfluxDBClient client = mock(InfluxDBClient.class);
    WriteApiBlocking api = mock(WriteApiBlocking.class);
    TelemetryWriter writer;

    @BeforeEach
    void setUp() {
        when(client.getWriteApiBlocking()).thenReturn(api);
        writer = new TelemetryWriter(client, new InfluxProperties("http://x", "t", "org", "bucket"));
    }

    /** The InfluxException subclasses need a retrofit Response; a mock with status() stubbed is simpler. */
    private static InfluxException withStatus(int status) {
        InfluxException e = mock(InfluxException.class);
        when(e.status()).thenReturn(status);
        return e;
    }

    private static TelemetryPoint p(Map<String, Object> fields) {
        return new TelemetryPoint(1, "pk", "dk", 1L, fields);
    }

    private void batchFails(int status) {
        InfluxException e = status == 0 ? new InfluxException(new IOException("down")) : withStatus(status);
        doThrow(e).when(api).writePoints(anyString(), anyString(), anyList());
    }

    @Test
    void unavailableStatusesOnBatchThrow() {
        for (int status : new int[]{500, 401, 404, 0}) {
            batchFails(status);
            assertThatThrownBy(() -> writer.write(List.of(p(Map.of("a", 1.0)))))
                    .as("status %d", status)
                    .isInstanceOf(TelemetryUnavailableException.class);
        }
    }

    @Test
    void perPointFallbackRejectsOnlyOffendingOriginalIndex() {
        batchFails(422);
        InfluxException rejection = withStatus(422);
        doNothing().doThrow(rejection).doNothing()
                .when(api).writePoint(anyString(), anyString(), any(Point.class));

        WriteResult r = writer.write(List.of(
                p(Map.of("a", 1.0)), p(Map.of("a", "x")), p(Map.of("a", 3.0))));

        assertThat(r.rejected()).extracting(WriteResult.Rejected::index).containsExactly(1);
    }

    @Test
    void serverErrorDuringPerPointLoopThrows() {
        batchFails(422);
        InfluxException serverError = withStatus(500);
        doNothing().doThrow(serverError)
                .when(api).writePoint(anyString(), anyString(), any(Point.class));

        assertThatThrownBy(() -> writer.write(List.of(p(Map.of("a", 1.0)), p(Map.of("a", 2.0)))))
                .isInstanceOf(TelemetryUnavailableException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unconvertiblePointsAreRejectedAtOriginalIndexAndRestIsWritten() {
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("a", null);

        WriteResult r = writer.write(List.of(
                p(Map.of("a", 1.0)),
                p(Map.of("a", List.of(1))),
                p(nullValue),
                p(null),
                p(Map.of("a", 5.0))));

        assertThat(r.rejected()).extracting(WriteResult.Rejected::index).containsExactly(1, 2, 3);
        ArgumentCaptor<List<Point>> cap = ArgumentCaptor.forClass(List.class);
        verify(api).writePoints(eq("bucket"), eq("org"), cap.capture());
        assertThat(cap.getValue()).hasSize(2);
    }

    @Test
    void conversionFailureAndFallbackRejectionCombineWithOriginalIndexes() {
        batchFails(422);
        InfluxException rejection = withStatus(422);
        doNothing().doThrow(rejection)
                .when(api).writePoint(anyString(), anyString(), any(Point.class));

        WriteResult r = writer.write(List.of(
                p(Map.of("a", List.of(1))),   // 0: conversion failure
                p(Map.of("a", 2.0)),          // 1: ok
                p(Map.of("a", 3.0))));        // 2: 422 in fallback

        assertThat(r.rejected()).extracting(WriteResult.Rejected::index).containsExactly(0, 2);
    }

    @Test
    void allUnconvertibleSkipsWrite() {
        WriteResult r = writer.write(List.of(p(Map.of("a", List.of()))));

        assertThat(r.rejected()).hasSize(1);
        verify(api, never()).writePoints(anyString(), anyString(), anyList());
    }
}
