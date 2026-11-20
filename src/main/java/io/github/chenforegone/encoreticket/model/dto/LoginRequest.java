package io.github.chenforegone.encoreticket.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Locale;

/**
 * 用户登录请求。
 *
 * <p>登录只支持用户名。用户名与注册请求保持相同的规范化规则，密码不做
 * trim 或大小写转换，避免改变用户实际输入的凭据。</p>
 *
 * @author c1342
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password
) {

    /** 规范化用户名；密码保持原样。 */
    public LoginRequest {
        username = username == null ? null : username.toLowerCase(Locale.ROOT);
    }

    /** 请求日志中不得出现明文密码。 */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=<redacted>]";
    }
}
