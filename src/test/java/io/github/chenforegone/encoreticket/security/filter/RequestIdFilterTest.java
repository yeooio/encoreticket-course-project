package io.github.chenforegone.encoreticket.security.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求生命周期中的日志关联及脱敏测试。
 *
 * @author c1342
 */
class RequestIdFilterTest {
    @Test
    void clearsMdcOnExceptionAndReusesIdForErrorAndAsyncDispatch() throws Exception {
        var filter = new RequestIdFilter();
        var request = new MockHttpServletRequest("POST", "/private-token");
        var response = new MockHttpServletResponse();
        Assertions.assertThrows(ServletException.class,
                () -> filter.doFilter(request, response, (req, res) -> {
                    MDC.put("other", "untouched");
                    throw new ServletException("private-password");
                }));
        String requestId = response.getHeader("X-Request-Id");
        try {
            assertThat(MDC.get("requestId")).isNull();
            assertThat(MDC.get("other")).isEqualTo("untouched");
            for (var type : new DispatcherType[]{DispatcherType.ERROR, DispatcherType.ASYNC}) {
                request.setDispatcherType(type);
                request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/private-token");
                filter.doFilter(request, response, (req, res) -> {
                    assertThat(MDC.get("requestId")).isEqualTo(requestId);
                    if (type == DispatcherType.ERROR) {
                        filter.doFilter(request, response, (nestedRequest, nestedResponse) ->
                                assertThat(MDC.get("requestId")).isEqualTo(requestId));
                        assertThat(MDC.get("requestId")).isEqualTo(requestId);
                    }
                });
                assertThat(MDC.get("requestId")).isNull();
                assertThat(response.getHeader("X-Request-Id")).isEqualTo(requestId);
            }
        } finally {
            MDC.remove("other");
        }
    }

    @Test
    void associatesBusinessLogsAndRestoresMdcBeforeLoggingFinalStatus() throws Exception {
        var filter = new RequestIdFilter();
        var request = new MockHttpServletRequest("GET", "/movies/private-token");
        request.setQueryString("password=private-password");
        var response = new MockHttpServletResponse();
        var logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        var capture = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        capture.start();
        logger.addAppender(capture);
        MDC.put("requestId", "outer");
        try {
            filter.doFilter(request, response, (req, res) -> {
                assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-Id"));
                logger.info("business");
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/movies/{id}");
            });
            assertThat(MDC.get("requestId")).isEqualTo("outer");
            response.setStatus(503);
            filter.requestDestroyed(new ServletRequestEvent(request.getServletContext(), request));
            filter.requestDestroyed(new ServletRequestEvent(request.getServletContext(), request));
            assertThat(capture.list).hasSize(2);
            assertThat(capture.list.getFirst().getMDCPropertyMap()).containsEntry("requestId", response.getHeader("X-Request-Id"));
            assertThat(capture.list.getLast().getFormattedMessage())
                    .contains("method=GET", "route=/movies/{id}", "status=503", "durationMs=")
                    .doesNotContain("private-token", "password");
            assertThat(MDC.get("requestId")).isEqualTo("outer");
        } finally {
            MDC.remove("requestId");
            logger.detachAppender(capture);
            capture.stop();
        }
    }
}
