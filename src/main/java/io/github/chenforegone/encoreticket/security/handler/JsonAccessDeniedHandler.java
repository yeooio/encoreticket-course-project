package io.github.chenforegone.encoreticket.security.handler;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.constant.RequestIdConstant;
import io.github.chenforegone.encoreticket.common.response.ApiErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;


/**
 * 已认证但权限不足时的处理器。
 *
 * <p>由 Spring Security 在抛出 {@link AccessDeniedException} 时调用，统一返回
 * JSON 格式的 403 响应体（{@link ApiErrorResponse}），替代默认的 HTML 错误页，
 * 并带上请求追踪 ID 以便定位日志。
 *
 * @author c1342
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private static final ErrorCode FORBIDDEN = ErrorCode.FORBIDDEN;
    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    /**
     * 将权限不足的结果写出为 JSON 格式的 403 响应。
     *
     * @param request               当前 HTTP 请求，用于读取 requestId
     * @param response              当前 HTTP 响应
     * @param accessDeniedException 触发本次处理的权限不足异常
     * @throws IOException     写出响应体失败时抛出
     * @throws ServletException Servlet 处理异常
     */
    @Override
    public void handle(@NonNull HttpServletRequest request, HttpServletResponse response,@NonNull AccessDeniedException accessDeniedException) throws IOException, ServletException {
        if (response.isCommitted()) {
            return;
        }
        Object value = request.getAttribute(RequestIdConstant.ATTRIBUTE_NAME);
        String requestId = value == null ? "" : value.toString();
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader(RequestIdConstant.RESPONSE_HEADER_NAME, requestId);
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(FORBIDDEN, requestId));
    }
}
