package dev.conduit.server.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** 生成产品 Key、设备 Key 与设备密钥：用 SecureRandom 从小写字母和数字中随机取字符。 */
@Component
public class KeyGenerator {

    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();

    public String productKey() {
        return next(16);
    }

    public String deviceKey() {
        return next(16);
    }

    /** 设备密钥：32 位随机串，明文只在创建/重置时返回一次。 */
    public String deviceSecret() {
        return next(32);
    }

    private String next(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }
}
