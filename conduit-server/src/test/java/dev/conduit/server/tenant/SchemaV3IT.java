package dev.conduit.server.tenant;

import dev.conduit.server.support.IntegrationTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V3 迁移集成测试：旧的 ADMIN 角色迁移为 TENANT_ADMIN（在独立 schema 中从 V2 迁移上来，测试后删除该 schema）；
 * 平台管理员没有租户、租户用户必须有租户；角色、状态、租户编码约束；新列默认值；删除租户时其用户随之删除。
 */
class SchemaV3IT extends IntegrationTestBase {

    @Autowired
    DataSource dataSource;

    @Test
    void migratesTheLegacyAdminRoleToTenantAdmin() {
        String schema = "mig_v3_" + unique("s");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).target("2").load().migrate();
            jdbc.update("insert into " + schema + ".tenant (code, name) values ('legacy', '旧租户')");
            jdbc.update("insert into " + schema + ".app_user (tenant_id, username, password_hash, role) "
                    + "select id, 'legacy-admin', 'h', 'ADMIN' from " + schema + ".tenant where code = 'legacy'");

            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();

            Map<String, Object> admin = jdbc.queryForMap("select role, must_change_password, display_name from "
                    + schema + ".app_user where username = 'legacy-admin'");
            assertThat(admin.get("role")).isEqualTo("TENANT_ADMIN");
            assertThat(admin.get("must_change_password")).isEqualTo(false);
            assertThat(admin.get("display_name")).isNull();
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void platformAdminsHaveNoTenantAndTenantUsersHaveOne() {
        long tenant = createTenant("sv3-");

        assertThatThrownBy(() -> jdbc.update("insert into app_user (tenant_id, username, password_hash, role) "
                + "values (?, ?, 'h', 'PLATFORM_ADMIN')", tenant, unique("u")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app_user (username, password_hash, role) "
                + "values (?, 'h', 'TENANT_ADMIN')", unique("u")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownRolesAndStatuses() {
        long tenant = createTenant("sv3-");

        assertThatThrownBy(() -> jdbc.update("insert into app_user (tenant_id, username, password_hash, role) "
                + "values (?, ?, 'h', 'ADMIN')", tenant, unique("u")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app_user (tenant_id, username, password_hash, role, status) "
                + "values (?, ?, 'h', 'TENANT_VIEWER', 'LOCKED')", tenant, unique("u")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update tenant set status = 'PAUSED' where id = ?", tenant))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidTenantCodes() {
        assertThatThrownBy(() -> jdbc.update("insert into tenant (code, name) values ('Bad_Code', '非法编码')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into tenant (code, name) values ('x', '太短')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newColumnsHaveDefaults() {
        long tenant = createTenant("sv3-");
        String username = unique("u");
        jdbc.update("insert into app_user (tenant_id, username, password_hash, role) values (?, ?, 'h', 'TENANT_VIEWER')",
                tenant, username);

        Map<String, Object> user = jdbc.queryForMap(
                "select display_name, must_change_password, updated_at from app_user where username = ?", username);
        assertThat(user.get("display_name")).isNull();
        assertThat(user.get("must_change_password")).isEqualTo(false);
        assertThat(user.get("updated_at")).isNotNull();
    }

    @Test
    void deletingATenantDeletesItsUsers() {
        long tenant = createTenant("sv3-");
        String username = createUser(tenant, "TENANT_ADMIN", true);

        jdbc.update("delete from tenant where id = ?", tenant);

        assertThat(jdbc.queryForObject("select count(*) from app_user where username = ?", Integer.class, username))
                .isZero();
    }
}
