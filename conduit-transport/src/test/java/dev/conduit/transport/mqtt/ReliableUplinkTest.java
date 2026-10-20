package dev.conduit.transport.mqtt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** ReliableUplink 单元测试：只接受大小受限的 JSON 对象与合法顶层字符串 id。 */
class ReliableUplinkTest {

    @Test
    void extractsTopLevelIdAndTimestampFromStandardAndFlatJson() {
        assertThat(extract("{\"id\":\"dev-1\",\"ts\":1,\"params\":{\"id\":\"nested\"}}"))
                .contains(new ReliableUplink.Metadata("dev-1", true));
        assertThat(extract("{\"ts\":1,\"Ua\":220.1,\"id\":\"flat-1\"}"))
                .contains(new ReliableUplink.Metadata("flat-1", true));
        assertThat(extract("{\"id\":\"without-ts\",\"params\":{}}"))
                .contains(new ReliableUplink.Metadata("without-ts", false));
    }

    @Test
    void rejectsMalformedNonObjectInvalidIdAndOversizedPayload() {
        assertThat(extract("not-json")).isEmpty();
        assertThat(extract("[]")).isEmpty();
        assertThat(extract("{\"id\":1,\"ts\":1}")).isEmpty();
        assertThat(extract("{\"id\":\"\",\"ts\":1}")).isEmpty();
        assertThat(extract("{\"id\":\"" + "x".repeat(129) + "\",\"ts\":1}")).isEmpty();
        assertThat(ReliableUplink.extract(new byte[ReliableUplink.MAX_PAYLOAD_BYTES + 1])).isEmpty();
    }

    private static java.util.Optional<ReliableUplink.Metadata> extract(String json) {
        return ReliableUplink.extract(json.getBytes(StandardCharsets.UTF_8));
    }
}
