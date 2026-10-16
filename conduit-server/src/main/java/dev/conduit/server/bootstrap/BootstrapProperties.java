package dev.conduit.server.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 启动初始化配置（前缀 conduit.bootstrap），只在首次创建账号时使用。
 *
 * @param adminUsername         默认租户管理员用户名（必填）
 * @param adminPassword         默认租户管理员明文密码（必填，入库前做 BCrypt 哈希）
 * @param platformAdminUsername 平台管理员用户名，默认 sysadmin（环境变量 CONDUIT_PLATFORM_ADMIN_USERNAME）
 * @param platformAdminPassword 平台管理员明文密码（环境变量 CONDUIT_PLATFORM_ADMIN_PASSWORD）；为空时不创建平台管理员，只打警告
 */
@ConfigurationProperties(prefix = "conduit.bootstrap")
public record BootstrapProperties(String adminUsername, String adminPassword,
                                  @DefaultValue("sysadmin") String platformAdminUsername,
                                  String platformAdminPassword) {
}
