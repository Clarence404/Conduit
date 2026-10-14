package dev.conduit.server.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

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
