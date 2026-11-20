package io.github.chenforegone.encoreticket.model.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 当前用户资料更新请求校验测试。
 *
 * @author c1342
 */
class UpdateProfileRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void allowsAvatarOnlyPatch() {
        UpdateProfileRequest request = new UpdateProfileRequest(
                null, "https://cdn.example.com/avatar.png", null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void accepts64CharacterNicknameAndRejects65Characters() {
        assertThat(validator.validate(new UpdateProfileRequest(
                "名".repeat(64), null, null, null))).isEmpty();
        assertThat(validator.validate(new UpdateProfileRequest(
                "名".repeat(65), null, null, null))).isNotEmpty();
    }

    @Test
    void rejectsBlankNicknameWhenProvided() {
        UpdateProfileRequest request = new UpdateProfileRequest(
                "   ", null, null, null);

        assertThat(validator.validate(request)).isNotEmpty();
    }
}
