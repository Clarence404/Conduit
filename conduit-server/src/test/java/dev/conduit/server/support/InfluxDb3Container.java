package dev.conduit.server.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** InfluxDB 3 Core，带离线管理员 token 文件，测试走真实鉴权。 */
public class InfluxDb3Container extends GenericContainer<InfluxDb3Container> {

    public static final String IMAGE = "influxdb:3.12.0-core";
    static final int PORT = 8181;
    private static final String TOKEN_FILE = "/tmp/admin-token.json";

    public InfluxDb3Container(String adminToken) {
        super(DockerImageName.parse(IMAGE));
        byte[] tokenFile = ("{\"token\":\"" + adminToken + "\",\"name\":\"_admin\"}").getBytes(StandardCharsets.UTF_8);
        // 文件属主为 root：0644 才能被容器内的 influxdb3 用户读取（服务端会打印权限告警，测试可接受）
        withCopyToContainer(Transferable.of(tokenFile, 0644), TOKEN_FILE);
        withCommand("influxdb3", "serve", "--node-id", "node0", "--object-store", "memory",
                "--admin-token-file", TOKEN_FILE, "--disable-authz", "health");
        withExposedPorts(PORT);
        waitingFor(Wait.forHttp("/health").forPort(PORT).forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    public String getUrl() {
        return "http://" + getHost() + ":" + getMappedPort(PORT);
    }
}
