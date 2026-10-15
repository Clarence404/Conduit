package dev.conduit.server.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 设备密钥哈希：SHA-256(salt + secret)。不用 bcrypt，避免重连风暴时鉴权 CPU 过载；
 * 密钥本身是 32 位随机串，熵足够，不需要慢哈希。
 */
@Component
public class SecretHasher {

    private final SecureRandom random = new SecureRandom();

    /** 生成 16 字节随机盐，十六进制表示。 */
    public String newSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    /** 计算 SHA-256(salt + secret) 并以十六进制返回。 */
    public String hash(String secret, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((salt + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 校验密钥是否与已存哈希一致，使用常量时间比较。 */
    public boolean matches(String secret, String salt, String expectedHash) {
        return MessageDigest.isEqual(
                hash(secret, salt).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
