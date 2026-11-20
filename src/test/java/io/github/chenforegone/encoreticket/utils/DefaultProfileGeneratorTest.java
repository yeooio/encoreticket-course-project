package io.github.chenforegone.encoreticket.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认昵称与单张内置头像契约。
 *
 * @author c1342
 */
class DefaultProfileGeneratorTest {
    @Test
    void generatesNicknameWithPrefixAndSixDigits() {
        var generator = new DefaultProfileGenerator();
        assertThat(generator.generateNickname()).matches("用户\\d{6}");
    }

    @Test
    void alwaysUsesOnePackagedLocalAvatar() {
        var generator = new DefaultProfileGenerator();
        for (int i = 0; i < 50; i++) {
            assertThat(generator.generateAvatar()).isEqualTo("/images/default-avatar.svg");
        }
        assertThat(getClass().getResource("/static/images/default-avatar.svg")).isNotNull();
    }
}
