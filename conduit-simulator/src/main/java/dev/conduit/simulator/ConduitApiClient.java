package dev.conduit.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conduit REST 的最小客户端，只覆盖模拟器与端到端测试需要的接口。登录后的令牌保存在实例中；
 * 平台管理员进入租户后令牌换成代入令牌，退出后换回平台令牌，因此一个实例同一时刻只代表一个身份。
 */
public class ConduitApiClient {

    private static final Logger log = LoggerFactory.getLogger(ConduitApiClient.class);
    private static final int PAGE_SIZE = 100;

    private final URI baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private String token;

    public ConduitApiClient(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 登录并保存 accessToken，之后的请求自动带 Bearer 头；状态码非 200 抛 IllegalStateException。 */
    public void login(String username, String password) {
        JsonNode body = send("POST", "/api/v1/auth/login", Map.of("username", username, "password", password), 200);
        token = body.get("accessToken").asText();
    }

    /** 同 {@link #findOrCreateProduct(String, String)}，新建时使用 standard-json 解码器。 */
    public String findOrCreateProduct(String name) {
        return findOrCreateProduct(name, "standard-json");
    }

    /** 按名称复用产品；新建时使用给定解码器。已有产品的解码器不同时只告警，不修改。 */
    public String findOrCreateProduct(String name, String codec) {
        for (int page = 1; ; page++) {
            JsonNode result = send("GET", "/api/v1/products?page=" + page + "&size=" + PAGE_SIZE, null, 200);
            for (JsonNode item : result.get("items")) {
                if (name.equals(item.get("name").asText())) {
                    String existing = item.path("codec").asText("");
                    if (!existing.isEmpty() && !existing.equals(codec)) {
                        log.warn("Product '{}' uses codec {} but the simulator sends {}", name, existing, codec);
                    }
                    log.debug("Product '{}' found: productKey={} codec={}", name, item.get("productKey").asText(),
                            existing);
                    return item.get("productKey").asText();
                }
            }
            if ((long) page * PAGE_SIZE >= result.get("total").asLong()) {
                break;
            }
        }
        String productKey = send("POST", "/api/v1/products", Map.of("name", name, "codec", codec), 201)
                .get("productKey").asText();
        log.debug("Product '{}' created: productKey={} codec={}", name, productKey, codec);
        return productKey;
    }

    /**
     * 产品已定义的属性标识。
     *
     * @return 按接口返回顺序的标识集合；产品没有物模型时为空
     */
    public Set<String> propertyIdentifiers(String productKey) {
        JsonNode list = send("GET", "/api/v1/products/" + encode(productKey) + "/properties", null, 200);
        Set<String> ids = new LinkedHashSet<>();
        list.forEach(p -> ids.add(p.get("identifier").asText()));
        return ids;
    }

    /** 导入物模型文档；mode 为服务端支持的导入模式（如 merge）。 */
    public void importThingModel(String productKey, JsonNode document, String mode) {
        send("PUT", "/api/v1/products/" + encode(productKey) + "/thing-model?mode=" + encode(mode), document, 200);
        log.debug("Thing model imported into {} (mode={}, properties={})", productKey, mode,
                document.path("properties").size());
    }

    /** 设备列表中模拟器关心的字段。 */
    public record DeviceSummary(String deviceKey, String name, String status) {
    }

    /** 租户列表中模拟器关心的字段。 */
    public record TenantSummary(String code, String name, String status) {
    }

    /** 产品列表中模拟器关心的字段；propertyCount 为物模型属性数。 */
    public record ProductSummary(String productKey, String name, String codec, int propertyCount) {
    }

    public List<String> listDeviceKeys(String productKey) {
        return listDevices(productKey).stream().map(DeviceSummary::deviceKey).toList();
    }

    /** 分页拉取产品下全部设备（每页 100 条，直到取完）。 */
    public List<DeviceSummary> listDevices(String productKey) {
        List<DeviceSummary> devices = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode result = send("GET", "/api/v1/devices?productKey=" + encode(productKey)
                    + "&page=" + page + "&size=" + PAGE_SIZE, null, 200);
            result.get("items").forEach(item -> devices.add(summary(item)));
            if ((long) page * PAGE_SIZE >= result.get("total").asLong()) {
                return devices;
            }
        }
    }

    /**
     * 产品下最多 limit 台启用中的设备：请求带 status=ENABLED，并按返回项的 status 再过滤一次；取够即停止翻页。
     */
    public List<DeviceSummary> listEnabledDevices(String productKey, int limit) {
        List<DeviceSummary> devices = new ArrayList<>();
        for (int page = 1; devices.size() < limit; page++) {
            JsonNode result = send("GET", "/api/v1/devices?productKey=" + encode(productKey)
                    + "&status=ENABLED&page=" + page + "&size=" + PAGE_SIZE, null, 200);
            for (JsonNode item : result.get("items")) {
                DeviceSummary device = summary(item);
                if (devices.size() < limit && "ENABLED".equals(device.status())) {
                    devices.add(device);
                }
            }
            if ((long) page * PAGE_SIZE >= result.get("total").asLong()) {
                break;
            }
        }
        return devices;
    }

    /** 全部启用中的租户（平台管理员）：请求带 status=ACTIVE，并按返回项的 status 再过滤一次。 */
    public List<TenantSummary> listActiveTenants() {
        List<TenantSummary> tenants = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode result = send("GET", "/api/v1/platform/tenants?status=ACTIVE&page=" + page + "&size="
                    + PAGE_SIZE, null, 200);
            for (JsonNode item : result.get("items")) {
                String status = item.path("status").asText("");
                if ("ACTIVE".equals(status)) {
                    tenants.add(new TenantSummary(item.get("code").asText(), item.path("name").asText(""), status));
                }
            }
            if ((long) page * PAGE_SIZE >= result.get("total").asLong()) {
                return tenants;
            }
        }
    }

    /** 平台管理员进入租户：之后的请求使用代入令牌（以租户管理员身份操作该租户）。 */
    public void enterTenant(String code) {
        token = send("POST", "/api/v1/platform/tenants/" + encode(code) + "/enter", null, 200)
                .get("accessToken").asText();
        log.debug("Entered tenant {}", code);
    }

    /** 退出代入的租户：之后的请求换回平台管理员自己的令牌。 */
    public void exitTenant() {
        token = send("POST", "/api/v1/auth/exit-tenant", null, 200).get("accessToken").asText();
        log.debug("Exited the impersonated tenant");
    }

    /** 当前租户的全部产品（分页取完）。 */
    public List<ProductSummary> listProducts() {
        List<ProductSummary> products = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode result = send("GET", "/api/v1/products?page=" + page + "&size=" + PAGE_SIZE, null, 200);
            result.get("items").forEach(item -> products.add(new ProductSummary(item.get("productKey").asText(),
                    item.path("name").asText(""), item.path("codec").asText(""), item.path("propertyCount").asInt())));
            if ((long) page * PAGE_SIZE >= result.get("total").asLong()) {
                return products;
            }
        }
    }

    /** 产品物模型的属性定义（类型、单位、上下限、小数位、枚举项），按接口返回顺序。 */
    public List<PropertyDef> properties(String productKey) {
        JsonNode list = send("GET", "/api/v1/products/" + encode(productKey) + "/properties", null, 200);
        List<PropertyDef> defs = new ArrayList<>();
        for (JsonNode p : list) {
            List<PropertyDef.EnumOption> items = new ArrayList<>();
            p.path("enumItems").forEach(e -> items.add(
                    new PropertyDef.EnumOption(e.get("value").asLong(), e.path("label").asText(""))));
            defs.add(new PropertyDef(p.get("identifier").asText(), p.path("name").asText(""),
                    p.path("dataType").asText(""), textOrNull(p, "unit"), doubleOrNull(p, "minValue"),
                    doubleOrNull(p, "maxValue"), intOrNull(p, "decimals"), items));
        }
        return defs;
    }

    /** 修改设备名称；接口要求同时提交状态，传入当前状态以保持不变。 */
    public void renameDevice(String deviceKey, String name, String status) {
        send("PUT", "/api/v1/devices/" + encode(deviceKey), Map.of("name", name, "status", status), 200);
        log.debug("Device {} renamed to '{}'", deviceKey, name);
    }

    public void deleteDevice(String deviceKey) {
        send("DELETE", "/api/v1/devices/" + encode(deviceKey), null, 204);
    }

    public void deleteProduct(String productKey) {
        send("DELETE", "/api/v1/products/" + encode(productKey), null, 204);
    }

    /** 创建设备；明文密钥只在此刻返回一次，调用方需自行保存。 */
    public DeviceCredentials createDevice(String productKey, String name) {
        JsonNode body = send("POST", "/api/v1/devices", Map.of("productKey", productKey, "name", name), 201);
        String deviceKey = body.get("device").get("deviceKey").asText();
        log.debug("Device created: deviceKey={} name='{}' (productKey={})", deviceKey, name, productKey);
        return new DeviceCredentials(productKey, deviceKey, body.get("deviceSecret").asText());
    }

    /** 重置设备密钥并返回新的明文密钥（旧密钥立即失效）。 */
    public DeviceCredentials resetSecret(String productKey, String deviceKey) {
        JsonNode body = send("POST", "/api/v1/devices/" + encode(deviceKey) + "/reset-secret", null, 200);
        log.debug("Device secret reset: deviceKey={}", deviceKey);
        return new DeviceCredentials(productKey, deviceKey, body.get("deviceSecret").asText());
    }

    private static DeviceSummary summary(JsonNode item) {
        return new DeviceSummary(item.get("deviceKey").asText(), item.path("name").asText(""),
                item.path("status").asText("ENABLED"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    /**
     * 发送 JSON 请求并解析响应体。状态码与 expectedStatus 不符时抛 IllegalStateException（含响应体）；
     * 204 之类空响应体由调用方忽略返回值。
     */
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
            log.debug("REST {} {} -> {}", method, path, response.statusCode());
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
