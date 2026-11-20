package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;

/**
 * 已识别用户但不允许继续执行当前操作的异常。
 *
 * @author c1342
 */
public final class ForbiddenException extends ApplicationException {

    /** 创建权限拒绝异常。 */
    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN);
    }
}
