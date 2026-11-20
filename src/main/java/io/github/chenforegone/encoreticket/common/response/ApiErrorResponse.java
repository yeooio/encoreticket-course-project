package io.github.chenforegone.encoreticket.common.response;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;

/** 统一错误响应。 */
public record ApiErrorResponse(
        String code,
        String message,
        Object data,
        String requestId
) {

    public static ApiErrorResponse of(ErrorCode errorCode, String requestId) {
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode must not be null");
        }
        return of(errorCode, errorCode.getDefaultMessage(), requestId);
    }

    public static ApiErrorResponse of(ErrorCode errorCode, String message, String requestId) {
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode must not be null");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return new ApiErrorResponse(errorCode.getCode(), message, null, requestId);
    }
}
