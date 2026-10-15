package dev.conduit.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 用户登录密码的哈希方案：BCrypt（设备密钥另见 SecretHasher，二者用途不同）。 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
