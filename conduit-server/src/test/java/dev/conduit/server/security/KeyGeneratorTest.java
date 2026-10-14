package dev.conduit.server.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KeyGeneratorTest {

    final KeyGenerator keys = new KeyGenerator();

    @Test
    void generatesKeysWithExpectedLengthAndAlphabet() {
        assertThat(keys.productKey()).matches("[a-z0-9]{16}");
        assertThat(keys.deviceKey()).matches("[a-z0-9]{16}");
        assertThat(keys.deviceSecret()).matches("[a-z0-9]{32}");
    }

    @Test
    void generatesDistinctValues() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(keys.deviceKey());
        }
        assertThat(seen).hasSize(1000);
    }
}
