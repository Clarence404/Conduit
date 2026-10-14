package dev.conduit.server.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretHasherTest {

    final SecretHasher hasher = new SecretHasher();

    @Test
    void saltIs32HexChars() {
        assertThat(hasher.newSalt()).matches("[0-9a-f]{32}");
    }

    @Test
    void hashIsDeterministicSha256Hex() {
        String salt = "00112233445566778899aabbccddeeff";
        assertThat(hasher.hash("secret", salt))
                .isEqualTo(hasher.hash("secret", salt))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void differentSaltsGiveDifferentHashes() {
        assertThat(hasher.hash("secret", hasher.newSalt())).isNotEqualTo(hasher.hash("secret", hasher.newSalt()));
    }

    @Test
    void matchesOnlyCorrectSecret() {
        String salt = hasher.newSalt();
        String hash = hasher.hash("right", salt);

        assertThat(hasher.matches("right", salt, hash)).isTrue();
        assertThat(hasher.matches("wrong", salt, hash)).isFalse();
        assertThat(hasher.matches("right", hasher.newSalt(), hash)).isFalse();
    }
}
