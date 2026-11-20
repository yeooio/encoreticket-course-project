package io.github.chenforegone.encoreticket.model.dto;

import io.github.chenforegone.encoreticket.common.validation.Password;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/**
 * 用户注册请求。
 *
 * <p>用户名在请求模型构建时统一转为小写，邮箱、手机号的空字符串统一为
 * {@code null}，避免同一账号因为大小写或空白值绕过唯一性校验。</p>
 *
 * @author c1342
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(
                regexp = "^[a-zA-Z][a-zA-Z0-9_]{3,63}$",
                message = "用户名须以字母开头，长度为 4-64 个字母、数字或下划线"
        )
        String username,

        @Password
        String password,

        @NotBlank(message = "确认密码不能为空")
        String confirmPassword,

        @NotBlank(message = "昵称不能为空")
        @Size(min = 1, max = 64, message = "昵称长度必须为 1-64 个字符")
        String nickname,

        @Size(max = 512, message = "头像地址长度不能超过 512 个字符")
        String avatar,

        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱长度不能超过 128 个字符")
        String email,

        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone
) {

    /**
     * 规范化用户输入。
     */
    public RegisterRequest {
        username = username == null ? null : username.toLowerCase(Locale.ROOT);
        nickname = normalizeRequired(nickname);
        avatar = normalizeOptional(avatar);
        email = normalizeOptional(email);
        phone = normalizeOptional(phone);
    }

    /**
     * Bean Validation 不支持仅凭字段注解表达跨字段相等关系，因此在请求模型上补充确认密码校验。
     */
    @AssertTrue(message = "两次输入的密码不一致")
    public boolean isPasswordsMatch() {
        return password != null && password.equals(confirmPassword);
    }


    /** 请求日志中不得出现明文密码或确认密码。 */
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username
                + ", password=<redacted>"
                + ", confirmPassword=<redacted>"
                + ", nickname=" + nickname
                + ", avatar=" + avatar
                + ", email=" + email
                + ", phone=" + phone + "]";
    }

    //去除无效输入,输入字符为"",返回null
    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    //去除无效输入,输入字符为"",依然输出""
    private static String normalizeRequired(String value) {
        return value == null ? null : value.strip();
    }
}
