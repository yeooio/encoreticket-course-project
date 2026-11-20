package io.github.chenforegone.encoreticket.security.handler;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.constant.RequestIdConstant;
import io.github.chenforegone.encoreticket.common.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;


/**
 * 未认证访问受保护资源时的认证入口点。
 *
 * <p>由 Spring Security 在缺少凭证或认证失败时调用，统一返回 JSON 格式的 401
 * 响应体（{@link ApiErrorResponse}），替代默认的登录跳转与 HTML 错误页，
 * 并带上请求追踪 ID 以便定位日志。
 *
 * @author c1342
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {


    private static final ErrorCode UNAUTHORIZED = ErrorCode.UNAUTHORIZED;
    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将认证失败的结果写出为 JSON 格式的 401 响应。
     *
     * @param request       当前 HTTP 请求，用于读取 requestId
     * @param response      当前 HTTP 响应
     * @param authException 触发本次处理的认证异常
     * @throws IOException 写出响应体失败时抛出
     */
    @Override
    public void commence(@NonNull HttpServletRequest request, HttpServletResponse response, @NonNull AuthenticationException authException) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Object value = request.getAttribute(RequestIdConstant.ATTRIBUTE_NAME);
        String requestId = value == null ? "" : value.toString();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader(RequestIdConstant.RESPONSE_HEADER_NAME, requestId);
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(UNAUTHORIZED, requestId));
    }
}
