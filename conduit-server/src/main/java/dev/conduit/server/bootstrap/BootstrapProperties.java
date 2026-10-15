package dev.conduit.server.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 初始管理员配置（前缀 conduit.bootstrap），仅在首次创建管理员时使用，两项都必填。
 *
 * @param adminUsername 初始管理员用户名
 * @param adminPassword 初始管理员明文密码（入库前做 BCrypt 哈希）
 */
@ConfigurationProperties(prefix = "conduit.bootstrap")
public record BootstrapProperties(String adminUsername, String adminPassword) {
}
