package io.github.chenforegone.encoreticket.model.dto;

import io.github.chenforegone.encoreticket.common.validation.Password;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/**
 * 修改当前用户密码请求。
 *
 * <p>需要校验旧密码，新密码必须符合 {@link Password} 强度规则，且两次输入的新密码必须一致。
 * 修改成功后当前用户所有登录令牌将被失效，需要重新登录。</p>
 *
 * @author c1342
 */
public record ChangePasswordRequest(
        @NotBlank(message = "旧密码不能为空") String oldPassword,
        @Password String newPassword,
        @NotBlank(message = "确认密码不能为空") String confirmPassword
) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[oldPassword=***, newPassword=***, confirmPassword=***]";
    }

    @AssertTrue(message = "两次输入的新密码不一致")
    public boolean isPasswordsMatch() {
        return newPassword != null && newPassword.equals(confirmPassword);
    }

    @AssertTrue(message = "新密码不能与旧密码相同")
    public boolean isNotSameAsOld() {
        return oldPassword == null || !oldPassword.equals(newPassword);
    }
}
