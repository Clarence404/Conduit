package dev.conduit.server.bootstrap;

import dev.conduit.server.auth.AppUser;
import dev.conduit.server.auth.AppUserMapper;
import dev.conduit.server.auth.Roles;
import dev.conduit.server.tenant.TenantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AdminBootstrap.ensurePlatformAdmin 单元测试：没有平台管理员时按配置创建；密码为空只警告不创建；已有平台管理员时不改动。 */
@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapTest {

    private final TenantMapper tenants = mock(TenantMapper.class);
    private final AppUserMapper users = mock(AppUserMapper.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private AdminBootstrap bootstrap(String platformPassword) {
        return new AdminBootstrap(new BootstrapProperties("admin", "admin-pass-123", "sysadmin", platformPassword),
                tenants, users, encoder);
    }

    @Test
    void createsThePlatformAdminWhenNoneExists() {
        when(users.exists(any())).thenReturn(false);
        when(encoder.encode("Platform-pass-1")).thenReturn("hashed");

        bootstrap("Platform-pass-1").ensurePlatformAdmin();

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).insert(saved.capture());
        AppUser admin = saved.getValue();
        assertThat(admin.getTenantId()).isNull();
        assertThat(admin.getUsername()).isEqualTo("sysadmin");
        assertThat(admin.getRole()).isEqualTo(Roles.PLATFORM_ADMIN);
        assertThat(admin.getPasswordHash()).isEqualTo("hashed");
        assertThat(admin.getStatus()).isEqualTo(AppUser.ACTIVE);
        assertThat(admin.getMustChangePassword()).isFalse();
    }

    @Test
    void onlyWarnsWhenThePasswordIsEmpty(CapturedOutput output) {
        when(users.exists(any())).thenReturn(false);

        bootstrap("  ").ensurePlatformAdmin();

        verify(users, never()).insert(any(AppUser.class));
        assertThat(output).contains("CONDUIT_PLATFORM_ADMIN_PASSWORD");
    }

    @Test
    void keepsAnExistingPlatformAdmin() {
        when(users.exists(any())).thenReturn(true);

        bootstrap("Platform-pass-1").ensurePlatformAdmin();

        verify(users, never()).insert(any(AppUser.class));
    }
}
