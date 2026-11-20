package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;

/** 目标资源不存在，由处理器映射为 HTTP 404。 */
public final class ResourceNotFoundException extends ApplicationException {

    public ResourceNotFoundException() {
        super(ErrorCode.RESOURCE_NOT_FOUND);
    }

    public ResourceNotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ResourceNotFoundException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
