package dev.conduit.server.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.conduit.server.auth.AppUser;
import dev.conduit.server.auth.AppUserMapper;
import dev.conduit.server.tenant.Tenant;
import dev.conduit.server.tenant.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {

    public static final String DEFAULT_TENANT_CODE = "default";
    public static final String ROLE_ADMIN = "ADMIN";

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

    @Override
    public void run(ApplicationArguments args) {
        if (props.adminUsername() == null || props.adminUsername().isBlank()
                || props.adminPassword() == null || props.adminPassword().isBlank()) {
            throw new IllegalStateException(
                    "conduit.bootstrap.admin-username and conduit.bootstrap.admin-password must be set");
        }

        Tenant tenant = tenants.selectOne(new LambdaQueryWrapper<Tenant>().eq(Tenant::getCode, DEFAULT_TENANT_CODE));
        if (tenant == null) {
            tenant = new Tenant();
            tenant.setCode(DEFAULT_TENANT_CODE);
            tenant.setName("Default");
            tenants.insert(tenant);
            log.info("Created default tenant id={}", tenant.getId());
        }

        boolean adminExists = users.exists(
                new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, props.adminUsername()));
        if (!adminExists) {
            AppUser admin = new AppUser();
            admin.setTenantId(tenant.getId());
            admin.setUsername(props.adminUsername());
            admin.setPasswordHash(encoder.encode(props.adminPassword()));
            admin.setRole(ROLE_ADMIN);
            users.insert(admin);
            log.info("Created admin user '{}'", props.adminUsername());
        }
    }
}
