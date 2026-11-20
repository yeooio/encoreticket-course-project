package io.github.chenforegone.encoreticket.security.filter;

import io.github.chenforegone.encoreticket.common.constant.ErrorContextConstant;
import io.github.chenforegone.encoreticket.common.exception.ExceptionLogSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * 将过滤器链中未处理的异常交给 MVC；未解析的故障继续交由容器处理。
 *
 * @author c1342
 */
@Component
public class ExceptionBridgeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ExceptionBridgeFilter.class);

    private final HandlerExceptionResolver handlerExceptionResolver;

    public ExceptionBridgeFilter(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (Exception exception) {
            request.setAttribute(ErrorContextConstant.ORIGINAL_EXCEPTION_ATTRIBUTE, exception);
            // 已提交的响应无法替换，异常仍须向外传播，不能吞掉。
            if (response.isCommitted()) {
                ExceptionLogSupport.logOnce(log, request, "Request failed after response committed", exception);
                throw exception;
            }
            // null 表示整条解析链未处理；调用本身的故障同样直接向外传播。
            if (handlerExceptionResolver.resolveException(request, response, null, exception) == null) {
                throw exception;
            }
        }
    }
}
