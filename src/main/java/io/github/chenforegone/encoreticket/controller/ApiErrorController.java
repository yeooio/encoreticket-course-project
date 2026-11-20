package io.github.chenforegone.encoreticket.controller;

import io.github.chenforegone.encoreticket.common.constant.ErrorContextConstant;
import io.github.chenforegone.encoreticket.common.constant.RequestIdConstant;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.ExceptionLogSupport;
import io.github.chenforegone.encoreticket.common.response.ApiErrorResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 将容器 ERROR 分派转换为统一的 API 错误响应，不重试已经失败的响应写出。
 *
 * @author c1342
 */
@RestController
public class ApiErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

    @RequestMapping("/error")
    public ResponseEntity<ApiErrorResponse> handleError(HttpServletRequest request, HttpServletResponse response) {
        if (response.isCommitted()) {
            return null;
        }

        // 容器会清理旧正文，但可能保留先前 Writer 使用的 ISO-8859-1 字符集。
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String requestId = requestId(request);
        int status = status(request);
        if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            logFailure(request, status);
        }

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(RequestIdConstant.RESPONSE_HEADER_NAME, requestId)
                .body(ApiErrorResponse.of(errorCode(status), requestId));
    }

    private static int status(HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.ERROR) {
            return HttpStatus.NOT_FOUND.value();
        }
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return status instanceof Integer value && value >= 400 && value <= 599
                ? value : HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private static ErrorCode errorCode(int status) {
        return switch (status) {
            case 400 -> ErrorCode.INVALID_ARGUMENT;
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 409 -> ErrorCode.RESOURCE_CONFLICT;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 422 -> ErrorCode.BUSINESS_RULE_VIOLATION;
            default -> status < 500 ? ErrorCode.HTTP_CLIENT_ERROR : ErrorCode.INTERNAL_ERROR;
        };
    }

    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdConstant.ATTRIBUTE_NAME);
        if (attribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(RequestIdConstant.ATTRIBUTE_NAME, requestId);
        return requestId;
    }

    private static void logFailure(HttpServletRequest request, int status) {
        Object original = request.getAttribute(ErrorContextConstant.ORIGINAL_EXCEPTION_ATTRIBUTE);
        Object dispatched = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (original instanceof Throwable exception) {
            ExceptionLogSupport.logOnce(log, request, "Original request failure", exception);
        }
        if (dispatched instanceof Throwable exception) {
            ExceptionLogSupport.logOnce(log, request, "Container error dispatch", exception);
        }
        if (!(original instanceof Throwable) && !(dispatched instanceof Throwable)) {
            ExceptionLogSupport.logOnce(log, request, "Container error dispatch, status=" + status, null);
        }
    }
}
