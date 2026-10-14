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
