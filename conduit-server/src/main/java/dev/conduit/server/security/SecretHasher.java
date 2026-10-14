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

    public String newSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    public String hash(String secret, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((salt + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean matches(String secret, String salt, String expectedHash) {
        return MessageDigest.isEqual(
                hash(secret, salt).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
