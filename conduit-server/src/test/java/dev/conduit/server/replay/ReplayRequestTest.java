package dev.conduit.server.replay;

import dev.conduit.server.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ReplayRequest 校验单元测试：必填、取值范围、from < to、跨度上限 6 小时。 */
class ReplayRequestTest {

    private static void expectBadRequest(Long from, Long to, String messagePart) {
        assertThatThrownBy(() -> new ReplayRequest(from, to).validated())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining(messagePart);
    }

    @Test
    void acceptsARangeWithinTheSpanLimit() {
        ReplayRequest request = new ReplayRequest(1760400000000L, 1760400000000L + ReplayRequest.MAX_SPAN_MILLIS);

        assertThat(request.validated()).isSameAs(request);
    }

    @Test
    void rejectsMissingBounds() {
        expectBadRequest(null, 1760400000000L, "from");
        expectBadRequest(1760400000000L, null, "to");
    }

    @Test
    void rejectsAnEmptyOrInvertedRange() {
        expectBadRequest(1760400000000L, 1760400000000L, "from 必须小于 to");
        expectBadRequest(1760400000001L, 1760400000000L, "from 必须小于 to");
    }

    @Test
    void rejectsBoundsOutsideTheAllowedMillisecondRange() {
        expectBadRequest(-1L, 1760400000000L, "from 超出范围");
        expectBadRequest(0L, ReplayRequest.MAX_MILLIS + 1, "to 超出范围");
    }

    @Test
    void rejectsASpanLongerThanSixHours() {
        expectBadRequest(0L, ReplayRequest.MAX_SPAN_MILLIS + 1, "6 小时");
    }
}
