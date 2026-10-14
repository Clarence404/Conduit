package dev.conduit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Conduit REST 的最小客户端，只覆盖模拟器与端到端测试需要的接口。 */
public class ConduitApiClient {

    private static final int PAGE_SIZE = 100;

    private final URI baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private String token;

    public ConduitApiClient(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void login(String username, String password) {
        JsonNode body = send("POST", "/api/v1/auth/login", Map.of("username", username, "password", password), 200);
        token = body.get("accessToken").asText();
    }

    public String findOrCreateProduct(String name) {
        for (int page = 1; ; page++) {
            JsonNode result = send("GET", "/api/v1/products?page=" + page + "&size=" + PAGE_SIZE, null, 200);
            for (JsonNode item : result.get("items")) {
                if (name.equals(item.get("name").asText())) {
                    return item.get("productKey").asText();
                }
            }
            if ((long) page * PAGE_SIZE >= result.get("total").asLong()) {
                break;
            }
        }
        return send("POST", "/api/v1/products", Map.of("name", name), 201).get("productKey").asText();
    }

    public List<String> listDeviceKeys(String productKey) {
        List<String> keys = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode result = send("GET", "/api/v1/devices?productKey=" + encode(productKey)
                    + "&page=" + page + "&size=" + PAGE_SIZE, null, 200);
            result.get("items").forEach(item -> keys.add(item.get("deviceKey").asText()));
            if ((long) page * PAGE_SIZE >= result.get("total").asLong()) {
                return keys;
            }
        }
    }

    public DeviceCredentials createDevice(String productKey, String name) {
        JsonNode body = send("POST", "/api/v1/devices", Map.of("productKey", productKey, "name", name), 201);
        return new DeviceCredentials(productKey, body.get("device").get("deviceKey").asText(),
                body.get("deviceSecret").asText());
    }

    public DeviceCredentials resetSecret(String productKey, String deviceKey) {
        JsonNode body = send("POST", "/api/v1/devices/" + encode(deviceKey) + "/reset-secret", null, 200);
        return new DeviceCredentials(productKey, deviceKey, body.get("deviceSecret").asText());
    }

    private JsonNode send(String method, String path, Object body, int expectedStatus) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(baseUrl.resolve(path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            request.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));

            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != expectedStatus) {
                throw new IllegalStateException(method + " " + path + " returned " + response.statusCode()
                        + ": " + new String(response.body(), StandardCharsets.UTF_8));
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
