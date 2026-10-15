package dev.conduit.server.telemetry;

import dev.conduit.server.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HistoryRequest 的单元测试：测点个数与格式、时间参数组合、跨度上下限（spec 2.1）。 */
class HistoryRequestTest {

    static final long NOW = 1760403600000L;
    static final long FROM = NOW - 3_600_000;

    private static void assertBadRequest(Executable call, String detail) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).contains(detail);
        });
    }

    private static String ids(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> "P" + i).collect(Collectors.joining(","));
    }

    @Test
    void defaultsToTheLastHour() {
        HistoryRequest r = HistoryRequest.parse("Ua", null, null, NOW);

        assertThat(r.from()).isEqualTo(NOW - 3_600_000);
        assertThat(r.to()).isEqualTo(NOW);
    }

    @Test
    void trimsAndDeduplicatesKeepingFirstOccurrence() {
        assertThat(HistoryRequest.parse(" Ua, Ia ,Ua", FROM, NOW, NOW).properties()).containsExactly("Ua", "Ia");
    }

    @Test
    void appliesTheLimitAfterDeduplication() {
        assertThat(HistoryRequest.parse(ids(8) + ",P1", FROM, NOW, NOW).properties()).hasSize(8);
    }

    @Test
    void rejectsMissingProperties() {
        assertBadRequest(() -> HistoryRequest.parse(null, FROM, NOW, NOW), "properties 不能为空");
        assertBadRequest(() -> HistoryRequest.parse("  ", FROM, NOW, NOW), "properties 不能为空");
    }

    @Test
    void rejectsMoreThanEightProperties() {
        assertBadRequest(() -> HistoryRequest.parse(ids(9), FROM, NOW, NOW), "最多查询 8 个测点");
    }

    @Test
    void rejectsIllegalIdentifiers() {
        assertBadRequest(() -> HistoryRequest.parse("1a", FROM, NOW, NOW), "测点标识不合法: 1a");
        assertBadRequest(() -> HistoryRequest.parse("a-b", FROM, NOW, NOW), "测点标识不合法");
        assertBadRequest(() -> HistoryRequest.parse("Ua,,Ia", FROM, NOW, NOW), "测点标识不合法");
        assertBadRequest(() -> HistoryRequest.parse("a".repeat(65), FROM, NOW, NOW), "测点标识不合法");
    }

    @Test
    void rejectsReservedColumnNames() {
        assertBadRequest(() -> HistoryRequest.parse("time", FROM, NOW, NOW), "测点标识不合法: time");
        assertBadRequest(() -> HistoryRequest.parse("Ua,tenant_id", FROM, NOW, NOW), "测点标识不合法: tenant_id");
    }

    @Test
    void rejectsOnlyOneTimeBound() {
        assertBadRequest(() -> HistoryRequest.parse("Ua", FROM, null, NOW), "from 与 to 须同时提供");
        assertBadRequest(() -> HistoryRequest.parse("Ua", null, NOW, NOW), "from 与 to 须同时提供");
    }

    @Test
    void rejectsFromNotBeforeTo() {
        assertBadRequest(() -> HistoryRequest.parse("Ua", NOW, NOW, NOW), "from 须小于 to");
        assertBadRequest(() -> HistoryRequest.parse("Ua", NOW, FROM, NOW), "from 须小于 to");
    }

    @Test
    void enforcesSpanFromOneMinuteToThirtyDaysInclusive() {
        long day = 86_400_000L;
        assertThat(HistoryRequest.parse("Ua", NOW - 60_000, NOW, NOW).from()).isEqualTo(NOW - 60_000);
        assertThat(HistoryRequest.parse("Ua", NOW - 30 * day, NOW, NOW).from()).isEqualTo(NOW - 30 * day);
        assertBadRequest(() -> HistoryRequest.parse("Ua", NOW - 59_999, NOW, NOW), "时间跨度须在 1 分钟到 30 天之间");
        assertBadRequest(() -> HistoryRequest.parse("Ua", NOW - 30 * day - 1, NOW, NOW),
                "时间跨度须在 1 分钟到 30 天之间");
    }
}
