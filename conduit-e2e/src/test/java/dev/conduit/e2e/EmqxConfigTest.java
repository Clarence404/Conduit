package dev.conduit.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EmqxConfig 单元测试：用部署用的 base.hocon.tpl 渲染出鉴权回调地址与内部 token，缺占位符时拒绝。 */
class EmqxConfigTest {

    @Test
    void rendersTheDeployTemplate() throws IOException {
        String template = Files.readString(E2eEnvironment.root().resolve("deploy/emqx/base.hocon.tpl"));

        String hocon = EmqxConfig.render(template, "http://dev-host.test:18080", "token-123");

        assertThat(hocon).contains("url = \"http://dev-host.test:18080/internal/mqtt/auth\"")
                .contains("\"X-Conduit-Internal-Token\" = \"token-123\"")
                .contains("client_connected = true")
                .doesNotContain("__CONDUIT_");
    }

    @Test
    void rejectsATemplateWithoutThePlaceholders() {
        assertThatThrownBy(() -> EmqxConfig.render("authentication = []", "http://h:1", "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("__CONDUIT_AUTH_URL__");
    }
}
