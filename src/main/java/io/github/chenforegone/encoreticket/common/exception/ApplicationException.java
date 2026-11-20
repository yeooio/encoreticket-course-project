package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

import java.util.Objects;

/**
 * 可预期应用异常的基类，不依赖 HTTP 协议。
 * 错误码默认文案可返回客户端，cause 仅供后端排查，不能直接输出到响应。
 */
public abstract class ApplicationException extends RuntimeException {

    private final ErrorCode errorCode;

    protected ApplicationException(ErrorCode errorCode) {
        this(errorCode, requireErrorCode(errorCode).getDefaultMessage(), null);
    }

    protected ApplicationException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, requireErrorCode(errorCode).getDefaultMessage(), cause);
    }

    /**
     * 使用指定的安全提示文案构造异常；文案只能由服务端代码提供，不能来自请求参数。
     */
    protected ApplicationException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    private ApplicationException(ErrorCode errorCode, String message, Throwable cause) {
        super(requireMessage(message), cause);
        this.errorCode = requireErrorCode(errorCode);
    }

    public final ErrorCode getErrorCode() {
        return errorCode;
    }

    public final HttpStatus getHttpStatus() {
        return errorCode.getHttpStatus();
    }

    private static ErrorCode requireErrorCode(ErrorCode errorCode) {
        return Objects.requireNonNull(errorCode, "errorCode");
    }

    private static String requireMessage(String message) {
        return Objects.requireNonNull(message, "message");
    }
}
