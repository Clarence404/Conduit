package dev.conduit.server.auth;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static dev.conduit.server.auth.JwtService.TokenType.ACCESS;
import static dev.conduit.server.auth.JwtService.TokenType.REFRESH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JwtService 单元测试：签发与解析往返、类型校验、过期、篡改、弱密钥，以及平台管理员（无租户）、代入与需改密码声明。 */
class JwtServiceTest {

    static final String SECRET = "unit-test-secret-unit-test-secret-0123";
    static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
    static final JwtProperties PROPS = new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(7), false);
    static final AuthPrincipal ALICE = new AuthPrincipal(7, 3L, "alice", Roles.TENANT_ADMIN);

    static JwtService at(Instant now) {
        return new JwtService(PROPS, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void roundTripsAccessToken() {
        JwtService jwt = at(T0);
        assertThat(jwt.parse(jwt.issue(ALICE, ACCESS), ACCESS)).isEqualTo(ALICE);
    }

    @Test
    void platformAdminTokenHasNoTenant() {
        JwtService jwt = at(T0);
        AuthPrincipal root = new AuthPrincipal(1, null, "sysadmin", Roles.PLATFORM_ADMIN);

        AuthPrincipal parsed = jwt.parse(jwt.issue(root, ACCESS), ACCESS);

        assertThat(parsed).isEqualTo(root);
        assertThat(parsed.tenantId()).isNull();
        assertThat(parsed.impersonating()).isFalse();
    }

    @Test
    void carriesTheImpersonatorAndThePasswordChangeFlag() {
        JwtService jwt = at(T0);
        AuthPrincipal entered = new AuthPrincipal(1, 3L, "sysadmin", Roles.TENANT_ADMIN, 1L, false);
        AuthPrincipal fresh = new AuthPrincipal(9, 3L, "east-viewer", Roles.TENANT_VIEWER, null, true);

        assertThat(jwt.parse(jwt.issue(entered, REFRESH), REFRESH)).isEqualTo(entered);
        assertThat(jwt.parse(jwt.issue(entered, ACCESS), ACCESS).impersonating()).isTrue();
        assertThat(jwt.parse(jwt.issue(fresh, ACCESS), ACCESS).mustChangePassword()).isTrue();
    }

    @Test
    void rejectsWrongTokenType() {
        JwtService jwt = at(T0);
        String refresh = jwt.issue(ALICE, REFRESH);
        assertThatThrownBy(() -> jwt.parse(refresh, ACCESS)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredAccessToken() {
        String token = at(T0).issue(ALICE, ACCESS);
        assertThatThrownBy(() -> at(T0.plus(Duration.ofMinutes(16))).parse(token, ACCESS))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void refreshTokenOutlivesAccessToken() {
        String token = at(T0).issue(ALICE, REFRESH);
        assertThat(at(T0.plus(Duration.ofDays(6))).parse(token, REFRESH)).isEqualTo(ALICE);
    }

    @Test
    void rejectsTamperedToken() {
        JwtService jwt = at(T0);
        String token = jwt.issue(ALICE, ACCESS);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThatThrownBy(() -> jwt.parse(tampered, ACCESS)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsShortSecret() {
        var weak = new JwtProperties("too-short", Duration.ofMinutes(15), Duration.ofDays(7), false);
        assertThatThrownBy(() -> new JwtService(weak, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
