package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;

/**
 * 请求不满足业务规则，由处理器映射为 HTTP 422。
 *
 * @author c1342
 */
public final class BusinessRuleException extends ApplicationException {

    public BusinessRuleException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BusinessRuleException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
