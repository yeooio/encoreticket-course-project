package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;

/** 请求格式或基础参数不合法，由处理器映射为 HTTP 400。 */
public final class InvalidRequestException extends ApplicationException {

    public InvalidRequestException(ErrorCode errorCode) {
        super(errorCode);
    }

    public InvalidRequestException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
