package dev.conduit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DemoProvisionerTest {

    HttpServer server;
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    final List<String> requestBodies = new CopyOnWriteArrayList<>();
    final List<String> queries = new CopyOnWriteArrayList<>();
    final AtomicInteger created = new AtomicInteger();
    volatile int propertyCount;

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
        queries.add(call + "?" + ex.getRequestURI().getQuery());
        requestBodies.add(call + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!call.endsWith("/auth/login")) {
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
        }
        String body;
        int status = 200;
        switch (call) {
            case "POST /api/v1/auth/login" -> body = "{\"accessToken\":\"tok\"}";
            case "GET /api/v1/products" -> body = """
                    {"items":[{"productKey":"pk-demo","name":"Demo Meter","codec":"standard-json"}],
                     "total":1,"page":1,"size":100}""";
            case "POST /api/v1/products" -> {
                status = 201;
                body = "{\"productKey\":\"pk-new\",\"codec\":\"flat-json\"}";
            }
            case "GET /api/v1/products/pk-demo" -> body = "{\"productKey\":\"pk-demo\",\"propertyCount\":"
                    + propertyCount + "}";
            case "PUT /api/v1/products/pk-demo/thing-model" -> body = "[]";
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
        ConduitApiClient api = new ConduitApiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        api.login("admin", "pw");
        return api;
    }

    @Test
    void reusesExistingProductResetsExistingDevicesAndCreatesTheRest() {
        List<DeviceCredentials> devices = DemoProvisioner.provision(client(), "Demo Meter", 4);

        assertThat(devices).extracting(DeviceCredentials::deviceKey)
                .containsExactly("dk-a", "dk-b", "dk-new1", "dk-new2");
        assertThat(devices).extracting(DeviceCredentials::secret).containsExactly("sa", "sb", "s1", "s2");
        assertThat(devices).allSatisfy(d -> assertThat(d.productKey()).isEqualTo("pk-demo"));
        assertThat(calls).doesNotContain("POST /api/v1/products");
        assertThat(authHeaders).containsOnly("Bearer tok");
    }

    @Test
    void usesOnlyAsManyExistingDevicesAsRequested() {
        List<DeviceCredentials> devices = DemoProvisioner.provision(client(), "Demo Meter", 1);

        assertThat(devices).extracting(DeviceCredentials::deviceKey).containsExactly("dk-a");
        assertThat(created).hasValue(0);
    }

    @Test
    void createsMissingProductWithTheRequestedCodec() {
        DemoProvisioner.provision(client(), "Flat Meter", 0, "flat-json");

        assertThat(requestBodies).anySatisfy(b -> assertThat(b)
                .startsWith("POST /api/v1/products ").contains("\"name\":\"Flat Meter\"")
                .contains("\"codec\":\"flat-json\""));
    }

    @Test
    void importsDemoThingModelWhenProductHasNoProperties() {
        propertyCount = 0;

        assertThat(DemoProvisioner.ensureThingModel(client(), "pk-demo")).isTrue();

        assertThat(queries).contains("PUT /api/v1/products/pk-demo/thing-model?mode=merge");
        assertThat(requestBodies).anySatisfy(b -> assertThat(b)
                .startsWith("PUT /api/v1/products/pk-demo/thing-model ").contains("\"identifier\":\"Ua\""));
    }

    @Test
    void skipsImportWhenProductAlreadyHasProperties() {
        propertyCount = 3;

        assertThat(DemoProvisioner.ensureThingModel(client(), "pk-demo")).isFalse();

        assertThat(calls).doesNotContain("PUT /api/v1/products/pk-demo/thing-model");
    }

    @Test
    void demoThingModelIsOnTheClasspath() {
        JsonNode model = DemoProvisioner.loadDemoThingModel();

        List<String> ids = new ArrayList<>();
        model.get("properties").forEach(p -> ids.add(p.get("identifier").asText()));
        assertThat(ids).containsExactly("Ua", "Ub", "Uc", "Ia", "Ib", "Ic", "EPimp");
        model.get("properties").forEach(p -> assertThat(p.get("dataType").asText()).isEqualTo("DOUBLE"));
        assertThat(model.get("properties").get(0).get("decimals").asInt()).isEqualTo(1);
        assertThat(model.get("properties").get(6).get("decimals").asInt()).isEqualTo(3);
    }
}
