package io.github.chenforegone.encoreticket.common.constant;

/**
 * 请求追踪 ID 常量。
 *
 * <p>一次请求的 requestId 会在请求属性与响应头之间传递同一个值，
 * 便于把应用日志与调用方看到的响应关联起来。
 *
 * @author c1342
 */
public final class RequestIdConstant {

    private RequestIdConstant() {
    }

    /** 存入 {@code HttpServletRequest} 属性的键名，供过滤器与异常处理器读取当前请求的追踪 ID。 */
    public static final String ATTRIBUTE_NAME = "requestId";

    /** 响应头名称，把本次请求的追踪 ID 返回给调用方。 */
    public static final String RESPONSE_HEADER_NAME = "X-Request-Id";
}