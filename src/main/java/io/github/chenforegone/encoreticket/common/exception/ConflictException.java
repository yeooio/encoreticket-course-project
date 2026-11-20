package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;

/**
 * 资源当前状态与操作冲突，由处理器映射为 HTTP 409。
 *
 * @author c1342
 */
public final class ConflictException extends ApplicationException {

    public ConflictException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ConflictException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
