package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;

/**
 * 登录凭据校验失败异常。
 *
 * <p>登录失败与受保护资源缺少凭据都使用 {@link ErrorCode#UNAUTHORIZED}；这里仅
 * 提供登录场景需要的安全且不泄露用户存在性的提示文案。</p>
 *
 * @author c1342
 */
public final class AuthenticationFailureException extends ApplicationException {

    /** 创建统一的登录失败异常。 */
    public AuthenticationFailureException() {
        super(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
    }
}
