package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.constant.RequestIdConstant;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.response.ApiErrorResponse;
import io.lettuce.core.RedisCommandTimeoutException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 将 MVC 可见异常转换为统一 API 响应，保留 Security 与容器错误出口的处理边界。
 *
 * @author c1342
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiErrorResponse> handleApplicationException(
            ApplicationException exception,
            HttpServletRequest request
    ) {
        rejectErrorDispatch(exception, request);
        if (exception instanceof DependencyUnavailableException) {
            ExceptionLogSupport.logOnce(log, request, "认证依赖暂时不可用", exception);
        }
        return response(exception.getHttpStatus(), exception.getErrorCode(), request, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) throws MethodArgumentNotValidException {
        rejectErrorDispatch(exception, request);
        return validationResponse(exception, request);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiErrorResponse> handleBindingException(
            BindException exception,
            HttpServletRequest request
    ) throws BindException {
        rejectErrorDispatch(exception, request);
        return validationResponse(exception, request);
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HandlerMethodValidationException.class
    })
    public ResponseEntity<ApiErrorResponse> handleMethodValidationException(
            Exception exception,
            HttpServletRequest request
    ) throws Exception {
        rejectErrorDispatch(exception, request);
        return response(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, request,
                ErrorCode.INVALID_ARGUMENT.getDefaultMessage());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(
            Exception exception,
            HttpServletRequest request
    ) throws Exception {
        rejectErrorDispatch(exception, request);
        return response(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, request,
                ErrorCode.INVALID_ARGUMENT.getDefaultMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) throws HttpRequestMethodNotSupportedException {
        rejectErrorDispatch(exception, request);
        ResponseEntity<ApiErrorResponse> response = response(
                HttpStatus.METHOD_NOT_ALLOWED,
                ErrorCode.METHOD_NOT_ALLOWED,
                request,
                ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage());
        String allow = exception.getSupportedHttpMethods() == null
                ? ""
                : exception.getSupportedHttpMethods().stream().map(Object::toString)
                .collect(Collectors.joining(", "));
        if (allow.isBlank()) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(headers -> headers.addAll(response.getHeaders()))
                .header("Allow", allow)
                .body(response.getBody());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request
    ) throws HttpMediaTypeNotSupportedException {
        rejectErrorDispatch(exception, request);
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE, request,
                ErrorCode.UNSUPPORTED_MEDIA_TYPE.getDefaultMessage());
    }

    @ExceptionHandler({
            NoHandlerFoundException.class,
            NoResourceFoundException.class
    })
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            Exception exception,
            HttpServletRequest request
    ) throws Exception {
        rejectErrorDispatch(exception, request);
        return response(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, request,
                ErrorCode.RESOURCE_NOT_FOUND.getDefaultMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateKey(
            DuplicateKeyException exception,
            HttpServletRequest request
    ) {
        rejectErrorDispatch(exception, request);
        return response(HttpStatus.CONFLICT, ErrorCode.RESOURCE_CONFLICT, request,
                ErrorCode.RESOURCE_CONFLICT.getDefaultMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        rejectErrorDispatch(exception, request);
        ExceptionLogSupport.logOnce(log, request, "Unexpected data integrity failure", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, request,
                ErrorCode.INTERNAL_ERROR.getDefaultMessage());
    }

    /** 交回 ExceptionTranslationFilter，按认证状态选择 401 或 403。 */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    public void propagateSecurityException(RuntimeException exception) {
        throw exception;
    }

    @ExceptionHandler({RedisConnectionFailureException.class,
            CannotGetJdbcConnectionException.class,
            RedisCommandTimeoutException.class})
    public ResponseEntity<ApiErrorResponse> handleDependencyUnavailable(
            RuntimeException exception, HttpServletRequest request) {
        rejectErrorDispatch(exception, request);
        ExceptionLogSupport.logOnce(log, request, "认证依赖暂时不可用", exception);
        return response(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, request,
                ErrorCode.SERVICE_UNAVAILABLE.getDefaultMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) throws Exception {
        rejectErrorDispatch(exception, request);
        ExceptionLogSupport.logOnce(log, request, "Unhandled request failure", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, request,
                ErrorCode.INTERNAL_ERROR.getDefaultMessage());
    }

    private static ResponseEntity<ApiErrorResponse> validationResponse(
            BindException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getAllErrors().stream()
                .map(GlobalExceptionHandler::validationMessage)
                .collect(Collectors.joining("；"));
        return response(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ARGUMENT, request,
                message.isBlank() ? ErrorCode.INVALID_ARGUMENT.getDefaultMessage() : message);
    }

    private static String validationMessage(ObjectError error) {
        String fallback = ErrorCode.INVALID_ARGUMENT.getDefaultMessage();
        // 当前 DTO 使用简单字段；集合/map 的动态路径可能含原始输入，须另行审阅。
        if (!(error instanceof FieldError fieldError)
                || !fieldError.getField().matches("[A-Za-z][A-Za-z0-9_]*")) {
            return fallback;
        }
        if (fieldError.isBindingFailure()) {
            return fieldError.getField() + ": 格式不正确";
        }
        if (!fieldError.contains(ConstraintViolation.class)) {
            return fallback;
        }

        ConstraintViolation<?> violation = fieldError.unwrap(ConstraintViolation.class);
        String template = violation.getConstraintDescriptor().getMessageTemplate();
        // 只采用项目 DTO 约束声明的固定文案，不采用插值结果或校验器运行时拼出的说明。
        if (!violation.getRootBeanClass().getPackageName()
                .equals("io.github.chenforegone.encoreticket.model.dto")
                || template == null || template.isBlank()
                || template.contains("{") || template.contains("}")
                || !template.equals(violation.getMessageTemplate())) {
            return fallback;
        }
        return fieldError.getField() + ": " + template;
    }

    private static ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            ErrorCode errorCode,
            HttpServletRequest request,
            String message
    ) {
        String requestId = requestId(request);
        return ResponseEntity.status(status)
                .header(RequestIdConstant.RESPONSE_HEADER_NAME, requestId)
                .body(ApiErrorResponse.of(errorCode, message, requestId));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdConstant.ATTRIBUTE_NAME);
        return value == null ? "" : value.toString();
    }

    //防止error控制器内部抛出异常然后全局异常处理器重复进行处理,导致频繁发生error跳转,当发现当前请求的dispatcher为ERROR的时候,直接打印日志,抛出异常不再重复路由到error页面进行处理
    private static <E extends Exception> void rejectErrorDispatch(E exception, HttpServletRequest request) throws E {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            ExceptionLogSupport.logOnce(log, request, "Error response failed", exception);
            throw exception;
        }
    }
}
