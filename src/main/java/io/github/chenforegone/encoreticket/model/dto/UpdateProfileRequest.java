package io.github.chenforegone.encoreticket.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新当前用户个人信息请求。
 *
 * <p>所有字段均为可选，客户端只传需要修改的字段；未传的字段保持原值不变。
 * 邮箱和手机号即使传入也不更新，改绑必须经过独立验证码接口。</p>
 *
 * @author c1342
 */
public record UpdateProfileRequest(
        @Size(min = 1, max = 64, message = "昵称长度必须为 1-64 个字符") String nickname,
        @Size(max = 512, message = "头像地址长度不能超过 512 个字符") String avatar,
        @Email(message = "邮箱格式不正确") @Size(max = 128, message = "邮箱长度不能超过 128 个字符") String email,
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone
) {

    public UpdateProfileRequest {
        nickname = nickname == null ? null : nickname.strip();
        avatar = normalizeOptional(avatar);
        email = normalizeOptional(email);
        phone = normalizeOptional(phone);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
