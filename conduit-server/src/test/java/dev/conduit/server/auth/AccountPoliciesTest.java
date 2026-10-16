package dev.conduit.server.auth;

import dev.conduit.server.tenant.TenantCodePolicy;
import dev.conduit.server.web.ApiException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 账号规则单元测试：用户名、密码、显示名称与租户编码的合法与非法取值（非法一律 400），以及租户角色判断。 */
class AccountPoliciesTest {

    private static void assertBadRequest(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void usernames() {
        for (String ok : List.of("abc", "Admin_01", "zhang.san", "a-b", "a".repeat(32))) {
            assertThat(UsernamePolicy.requireValid(ok)).isEqualTo(ok);
        }
        for (String bad : Arrays.asList(null, "", "ab", "1abc", "_abc", "abc def", "张三丰", "a".repeat(33))) {
            assertBadRequest(() -> UsernamePolicy.requireValid(bad));
        }
    }

    @Test
    void passwords() {
        for (String ok : List.of("abcdefg1", "Passw0rd!", "1234567a", "a".repeat(63) + "1")) {
            assertThat(PasswordPolicy.requireValid(ok)).isEqualTo(ok);
        }
        for (String bad : Arrays.asList(null, "", "abc1234", "abcdefgh", "12345678", "a".repeat(64) + "1")) {
            assertBadRequest(() -> PasswordPolicy.requireValid(bad));
        }
    }

    @Test
    void displayNames() {
        assertThat(DisplayNamePolicy.normalize(null)).isNull();
        assertThat(DisplayNamePolicy.normalize("   ")).isNull();
        assertThat(DisplayNamePolicy.normalize(" 华东运维 张工 ")).isEqualTo("华东运维 张工");
        assertThat(DisplayNamePolicy.normalize("名".repeat(64))).hasSize(64);
        assertBadRequest(() -> DisplayNamePolicy.normalize("名".repeat(65)));
    }

    @Test
    void tenantCodes() {
        for (String ok : List.of("default", "ab", "east-1", "a" + "b".repeat(31))) {
            assertThat(TenantCodePolicy.requireValid(ok)).isEqualTo(ok);
        }
        for (String bad : Arrays.asList(null, "", "a", "1abc", "Abc", "a_b", "-ab", "a".repeat(33), "华东")) {
            assertBadRequest(() -> TenantCodePolicy.requireValid(bad));
        }
    }

    @Test
    void tenantRoles() {
        assertThat(Roles.isTenantRole(Roles.TENANT_ADMIN)).isTrue();
        assertThat(Roles.isTenantRole(Roles.TENANT_VIEWER)).isTrue();
        assertThat(Roles.isTenantRole(Roles.PLATFORM_ADMIN)).isFalse();
        assertThat(Roles.isTenantRole(null)).isFalse();
    }
}
