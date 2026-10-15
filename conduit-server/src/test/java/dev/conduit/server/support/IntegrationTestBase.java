package dev.conduit.server.support;

import com.jayway.jsonpath.JsonPath;
import dev.conduit.server.security.SecretHasher;
import dev.conduit.server.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 集成测试基类：启动完整 Spring 上下文（Testcontainers 提供 PG / Redis / InfluxDB），提供 MockMvc 与直接写库造设备、定义属性等公共辅助。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected SecretHasher secretHasher;

    protected record TestDevice(long tenantId, String productKey, String deviceKey, String secret) {
    }

    /** 直接写库创建「产品 + 设备」，用于不经过 API 的测试。 */
    protected TestDevice createDevice(long tenantId, String status) {
        String productKey = unique("pk");
        String deviceKey = unique("dk");
        String secret = "secret-" + deviceKey;
        String salt = secretHasher.newSalt();
        Long productId = jdbc.queryForObject(
                "insert into product (tenant_id, product_key, name) values (?, ?, 'test product') returning id",
                Long.class, tenantId, productKey);
        jdbc.update("insert into device (tenant_id, product_id, device_key, name, secret_hash, secret_salt, status) "
                        + "values (?, ?, ?, 'test device', ?, ?, ?)",
                tenantId, productId, deviceKey, secretHasher.hash(secret, salt), salt, status);
        return new TestDevice(tenantId, productKey, deviceKey, secret);
    }

    protected void setCodec(String productKey, String codec) {
        jdbc.update("update product set codec = ? where product_key = ?", codec, productKey);
    }

    /** 直接写库定义属性（产品所在租户）。物模型缓存只在流水线首次处理该产品后才有条目，先定义再上报即可。 */
    protected void insertProperty(String productKey, String identifier, String name, String dataType, String unit,
                                  Double min, Double max, Integer decimals, String enumItemsJson) {
        jdbc.update("insert into product_property (tenant_id, product_id, identifier, name, data_type, unit, "
                        + "min_value, max_value, decimals, enum_items) "
                        + "select tenant_id, id, ?, ?, ?, cast(? as varchar), cast(? as double precision), "
                        + "cast(? as double precision), cast(? as smallint), cast(? as jsonb) "
                        + "from product where product_key = ?",
                identifier, name, dataType, unit, min, max, decimals, enumItemsJson, productKey);
    }

    protected void defineProperty(String productKey, String identifier, String dataType, Double min, Double max) {
        insertProperty(productKey, identifier, identifier, dataType, null, min, max, null, null);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    protected static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    protected long createTenant(String codePrefix) {
        String code = unique(codePrefix);
        return jdbc.queryForObject("insert into tenant (code, name) values (?, ?) returning id",
                Long.class, code, code);
    }

    protected String loginAsAdmin() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin-pass-123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected long defaultTenantId() {
        return jdbc.queryForObject("select id from tenant where code = 'default'", Long.class);
    }
}
