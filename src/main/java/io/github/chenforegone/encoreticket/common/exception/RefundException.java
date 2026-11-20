package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.ApplicationException;

/**
 * 退款业务安全错误。
 * @author c1342
 */
public final class RefundException extends ApplicationException {
    public RefundException(ErrorCode error) { super(error); }
}
