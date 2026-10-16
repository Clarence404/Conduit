package dev.conduit.server.bootstrap;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** 启动初始化集成测试：默认租户与租户管理员 admin、按配置创建的平台管理员 sysadmin（无租户），以及重复执行的幂等性。 */
class AdminBootstrapIT extends IntegrationTestBase {

    @Autowired
    AdminBootstrap bootstrap;

    @Autowired
    PasswordEncoder encoder;

    @Test
    void createsDefaultTenantAndAdminOnStartup() {
        Long tenantId = jdbc.queryForObject("select id from tenant where code = 'default'", Long.class);
        var admin = jdbc.queryForMap("select * from app_user where username = 'admin'");

        assertThat(admin.get("tenant_id")).isEqualTo(tenantId);
        assertThat(admin.get("role")).isEqualTo("TENANT_ADMIN");
        assertThat(admin.get("must_change_password")).isEqualTo(false);
        assertThat(encoder.matches("admin-pass-123", (String) admin.get("password_hash"))).isTrue();
    }

    @Test
    void createsThePlatformAdminFromSettings() {
        var sysadmin = jdbc.queryForMap("select * from app_user where username = 'sysadmin'");

        assertThat(sysadmin.get("tenant_id")).isNull();
        assertThat(sysadmin.get("role")).isEqualTo("PLATFORM_ADMIN");
        assertThat(sysadmin.get("status")).isEqualTo("ACTIVE");
        assertThat(sysadmin.get("must_change_password")).isEqualTo(false);
        assertThat(encoder.matches("sysadmin-pass-123", (String) sysadmin.get("password_hash"))).isTrue();
    }

    @Test
    void isIdempotent() throws Exception {
        bootstrap.run(null);
        bootstrap.run(null);

        assertThat(jdbc.queryForObject("select count(*) from tenant where code = 'default'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from app_user where username = 'admin'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from app_user where username = 'sysadmin'", Integer.class)).isEqualTo(1);
    }
}
