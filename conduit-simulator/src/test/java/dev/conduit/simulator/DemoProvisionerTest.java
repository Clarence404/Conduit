package dev.conduit.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DemoProvisionerTest {

    HttpServer server;
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    final AtomicInteger created = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String call = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
        calls.add(call);
        if (!call.endsWith("/auth/login")) {
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
        }
        String body;
        int status = 200;
        switch (call) {
            case "POST /api/v1/auth/login" -> body = "{\"accessToken\":\"tok\"}";
            case "GET /api/v1/products" -> body = """
                    {"items":[{"productKey":"pk-demo","name":"Demo Meter"}],"total":1,"page":1,"size":100}""";
            case "GET /api/v1/devices" -> body = """
                    {"items":[{"deviceKey":"dk-b"},{"deviceKey":"dk-a"}],"total":2,"page":1,"size":100}""";
            case "POST /api/v1/devices/dk-a/reset-secret" -> body = "{\"deviceKey\":\"dk-a\",\"deviceSecret\":\"sa\"}";
            case "POST /api/v1/devices/dk-b/reset-secret" -> body = "{\"deviceKey\":\"dk-b\",\"deviceSecret\":\"sb\"}";
            case "POST /api/v1/devices" -> {
                status = 201;
                int n = created.incrementAndGet();
                body = "{\"device\":{\"deviceKey\":\"dk-new" + n + "\"},\"deviceSecret\":\"s" + n + "\"}";
            }
            default -> {
                status = 404;
                body = "{}";
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private ConduitApiClient client() {
        return new ConduitApiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @Test
    void reusesExistingProductResetsExistingDevicesAndCreatesTheRest() {
        ConduitApiClient api = client();
        api.login("admin", "pw");

        List<DeviceCredentials> devices = DemoProvisioner.provision(api, "Demo Meter", 4);

        assertThat(devices).extracting(DeviceCredentials::deviceKey)
                .containsExactly("dk-a", "dk-b", "dk-new1", "dk-new2");
        assertThat(devices).extracting(DeviceCredentials::secret).containsExactly("sa", "sb", "s1", "s2");
        assertThat(devices).allSatisfy(d -> assertThat(d.productKey()).isEqualTo("pk-demo"));
        assertThat(calls).doesNotContain("POST /api/v1/products");
        assertThat(authHeaders).containsOnly("Bearer tok");
    }

    @Test
    void usesOnlyAsManyExistingDevicesAsRequested() {
        ConduitApiClient api = client();
        api.login("admin", "pw");

        List<DeviceCredentials> devices = DemoProvisioner.provision(api, "Demo Meter", 1);

        assertThat(devices).extracting(DeviceCredentials::deviceKey).containsExactly("dk-a");
        assertThat(created).hasValue(0);
    }
}
