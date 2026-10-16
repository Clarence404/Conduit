package dev.conduit.server.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.conduit.server.auth.AppUser;
import dev.conduit.server.auth.AppUserMapper;
import dev.conduit.server.auth.Roles;
import dev.conduit.server.tenant.Tenant;
import dev.conduit.server.tenant.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动初始化（幂等）：保证默认租户 default 与其租户管理员存在；没有任何平台管理员时按配置创建一个。
 * 已存在的账号不做任何改动（不会重置密码）。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    public static final String DEFAULT_TENANT_CODE = Tenant.DEFAULT_CODE;

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final BootstrapProperties props;
    private final TenantMapper tenants;
    private final AppUserMapper users;
    private final PasswordEncoder encoder;

    public AdminBootstrap(BootstrapProperties props, TenantMapper tenants, AppUserMapper users,
                          PasswordEncoder encoder) {
        this.props = props;
        this.tenants = tenants;
        this.users = users;
        this.encoder = encoder;
    }

    /**
     * 依次保证默认租户、其租户管理员与平台管理员存在。
     *
     * @throws IllegalStateException 默认租户管理员的用户名或密码未配置，进程启动失败
     */
    @Override
    public void run(ApplicationArguments args) {
        if (props.adminUsername() == null || props.adminUsername().isBlank()
                || props.adminPassword() == null || props.adminPassword().isBlank()) {
            throw new IllegalStateException(
                    "conduit.bootstrap.admin-username and conduit.bootstrap.admin-password must be set");
        }
        Tenant tenant = ensureDefaultTenant();
        ensureTenantAdmin(tenant);
        ensurePlatformAdmin();
    }

    /** 缺失时创建默认租户（编码 default，名称 Default）。 */
    private Tenant ensureDefaultTenant() {
        Tenant tenant = tenants.selectOne(new LambdaQueryWrapper<Tenant>().eq(Tenant::getCode, DEFAULT_TENANT_CODE));
        if (tenant == null) {
            tenant = new Tenant();
            tenant.setCode(DEFAULT_TENANT_CODE);
            tenant.setName("Default");
            tenant.setStatus(Tenant.ACTIVE);
            tenants.insert(tenant);
            log.info("Created default tenant id={}", tenant.getId());
        }
        return tenant;
    }

    /** 缺失时在默认租户下创建租户管理员（不要求首次登录改密码）。 */
    private void ensureTenantAdmin(Tenant tenant) {
        boolean exists = users.exists(
                new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, props.adminUsername()));
        if (!exists) {
            AppUser admin = new AppUser();
            admin.setTenantId(tenant.getId());
            admin.setUsername(props.adminUsername());
            admin.setPasswordHash(encoder.encode(props.adminPassword()));
            admin.setRole(Roles.TENANT_ADMIN);
            admin.setStatus(AppUser.ACTIVE);
            admin.setMustChangePassword(false);
            users.insert(admin);
            log.info("Created tenant admin '{}' in the default tenant", props.adminUsername());
        }
    }

    /**
     * 没有任何平台管理员时，按 platformAdminUsername / platformAdminPassword 创建一个（不属于任何租户）。
     * 密码为空时只打一条 WARN、不创建，不阻止启动；用户名已被租户用户占用时同样只警告。
     */
    void ensurePlatformAdmin() {
        if (users.exists(new LambdaQueryWrapper<AppUser>().eq(AppUser::getRole, Roles.PLATFORM_ADMIN))) {
            return;
        }
        String username = props.platformAdminUsername();
        String password = props.platformAdminPassword();
        if (password == null || password.isBlank()) {
            log.warn("No platform admin exists and CONDUIT_PLATFORM_ADMIN_PASSWORD is empty; "
                    + "platform admin '{}' was not created", username);
            return;
        }
        if (users.exists(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username))) {
            log.warn("Username '{}' is already taken by a tenant user; platform admin was not created", username);
            return;
        }
        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setPasswordHash(encoder.encode(password));
        admin.setRole(Roles.PLATFORM_ADMIN);
        admin.setStatus(AppUser.ACTIVE);
        admin.setMustChangePassword(false);
        users.insert(admin);
        log.info("Created platform admin '{}'", username);
    }
}
