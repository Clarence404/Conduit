package dev.conduit.server.bootstrap;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(admin.get("role")).isEqualTo("ADMIN");
        assertThat(encoder.matches("admin-pass-123", (String) admin.get("password_hash"))).isTrue();
    }

    @Test
    void isIdempotent() throws Exception {
        bootstrap.run(null);
        bootstrap.run(null);

        assertThat(jdbc.queryForObject("select count(*) from tenant where code = 'default'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from app_user where username = 'admin'", Integer.class)).isEqualTo(1);
    }
}
