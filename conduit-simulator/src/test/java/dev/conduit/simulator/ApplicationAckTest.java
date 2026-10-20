package dev.conduit.simulator;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 平台业务回执解析测试。 */
class ApplicationAckTest {

    @Test
    void acceptsAStringIdAndRejectsMalformedPayloads() {
        assertThat(id("{\"id\":\"device-1\",\"ts\":1}")).contains("device-1");
        assertThat(id("{\"id\":1}")).isEmpty();
        assertThat(id("{\"ts\":1}")).isEmpty();
        assertThat(id("bad")).isEmpty();
    }

    private static java.util.Optional<String> id(String json) {
        return ApplicationAck.id(json.getBytes(StandardCharsets.UTF_8));
    }
}
