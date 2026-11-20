package io.github.chenforegone.encoreticket.security.filter;

import io.github.chenforegone.encoreticket.common.constant.RequestIdConstant;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 为每次 Servlet 分派关联请求日志，在整个请求销毁时记录最终摘要。
 *
 * <p>异步工作线程不自动继承 MDC；ASYNC/ERROR 分派重新绑定同一标识并恢复线程原值。
 * 摘要不读取原始 URI、查询参数、请求体或认证头。
 *
 * @author c1342
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter implements ServletRequestListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final String STATE_ATTRIBUTE = RequestIdFilter.class.getName() + ".state";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterNestedErrorDispatch(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response, @NonNull FilterChain chain) throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response, @NonNull FilterChain chain) throws ServletException, IOException {
        RequestState state = (RequestState) request.getAttribute(STATE_ATTRIBUTE);
        if (state == null) {
            state = new RequestState(request.getMethod(), response);
            request.setAttribute(STATE_ATTRIBUTE, state);
            request.setAttribute(RequestIdConstant.ATTRIBUTE_NAME, state.requestId);
        }
        state.response = response;
        response.setHeader(RequestIdConstant.RESPONSE_HEADER_NAME, state.requestId);
        String previous = MDC.get(RequestIdConstant.ATTRIBUTE_NAME);
        MDC.put(RequestIdConstant.ATTRIBUTE_NAME, state.requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (request.getDispatcherType() == DispatcherType.REQUEST) {
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                if (pattern instanceof String route) {
                    state.route = route;
                }
            }
            restoreMdc(previous);
        }
    }

    @Override
    public void requestDestroyed(ServletRequestEvent event) {
        Object attribute = event.getServletRequest().getAttribute(STATE_ATTRIBUTE);
        if (!(attribute instanceof RequestState state) || !state.logged.compareAndSet(false, true)) {
            return;
        }
        String previous = MDC.get(RequestIdConstant.ATTRIBUTE_NAME);
        MDC.put(RequestIdConstant.ATTRIBUTE_NAME, state.requestId);
        try {
            LOGGER.info("HTTP request method={}, route={}, status={}, durationMs={}",
                    state.method, state.route, state.response.getStatus(),
                    (System.nanoTime() - state.startedNanos) / 1_000_000);
        } finally {
            restoreMdc(previous);
        }
    }

    private static void restoreMdc(String previous) {
        if (previous == null) {
            MDC.remove(RequestIdConstant.ATTRIBUTE_NAME);
        } else {
            MDC.put(RequestIdConstant.ATTRIBUTE_NAME, previous);
        }
    }

    /**
     * 仅保存受控的摘要字段，不持有凭据或正文。
     *
     * @author c1342
     */
    private static final class RequestState {
        private final String requestId = UUID.randomUUID().toString();
        private final long startedNanos = System.nanoTime();
        private final AtomicBoolean logged = new AtomicBoolean();
        private final String method;
        private volatile String route = "<unmapped>";
        private volatile HttpServletResponse response;

        private RequestState(String method, HttpServletResponse response) {
            this.method = method;
            this.response = response;
        }
    }
}
