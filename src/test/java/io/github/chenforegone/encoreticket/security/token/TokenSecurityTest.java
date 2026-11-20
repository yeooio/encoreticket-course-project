package io.github.chenforegone.encoreticket.security.token;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Token generation and hashing tests. */
class TokenSecurityTest {

    @Test
    void hashesTokenWithSha256WithoutStoringTheOriginalValue() {
        assertThat(new TokenHasher().hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void rejectsBlankTokenForHashing() {
        assertThatThrownBy(() -> new TokenHasher().hash("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatesUrlSafe256BitTokens() {
        TokenGenerator generator = new TokenGenerator();
        String first = generator.generate();
        String second = generator.generate();

        assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(second).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(second).isNotEqualTo(first);
    }
}
