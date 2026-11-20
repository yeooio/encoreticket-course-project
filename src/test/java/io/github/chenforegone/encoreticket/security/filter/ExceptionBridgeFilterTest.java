package io.github.chenforegone.encoreticket.security.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.chenforegone.encoreticket.common.constant.ErrorContextConstant;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证桥接器只转交异常，无法解析时保持传播语义。
 *
 * @author c1342
 */
class ExceptionBridgeFilterTest {

    @Test
    void delegatesFilterExceptionWithoutWritingAnotherResponse() throws Exception {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        when(resolver.resolveException(any(), any(), isNull(), any())).thenAnswer(invocation -> {
            MockHttpServletResponse response = invocation.getArgument(1);
            response.setStatus(500);
            response.getWriter().write("resolved");
            return new ModelAndView();
        });
        ExceptionBridgeFilter filter = new ExceptionBridgeFilter(resolver);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalStateException failure = new IllegalStateException("redis unavailable");

        filter.doFilter(request, response, failingChain(failure));

        verify(resolver).resolveException(request, response, null, failure);
        assertThat(request.getAttribute(ErrorContextConstant.ORIGINAL_EXCEPTION_ATTRIBUTE)).isSameAs(failure);
        assertThat(response.getContentAsString()).isEqualTo("resolved");
        assertThat(response.getStatus()).isEqualTo(500);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void propagatesOriginalFailureWhenResolverCannotHandleException(Exception failure) throws Exception {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        ExceptionBridgeFilter filter = new ExceptionBridgeFilter(resolver);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/protected");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, failingChain(failure))).isSameAs(failure);

        assertThat(request.getAttribute(ErrorContextConstant.ORIGINAL_EXCEPTION_ATTRIBUTE)).isSameAs(failure);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void letsResolverFailureEscapeAndKeepsOriginalFailure() throws Exception {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        IllegalStateException resolverFailure = new IllegalStateException("resolver unavailable");
        when(resolver.resolveException(any(), any(), isNull(), any())).thenThrow(resolverFailure);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        IOException original = new IOException("original");

        assertThatThrownBy(() -> new ExceptionBridgeFilter(resolver)
                .doFilter(request, response, failingChain(original))).isSameAs(resolverFailure);

        assertThat(request.getAttribute(ErrorContextConstant.ORIGINAL_EXCEPTION_ATTRIBUTE)).isSameAs(original);
        assertThat(response.getContentAsString()).isEmpty();
        verify(resolver).resolveException(request, response, null, original);
    }

    @Test
    void propagatesFailureWithoutResolvingAfterResponseIsCommitted() throws Exception {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getWriter().write("already sent");
        response.flushBuffer();
        IllegalStateException failure = new IllegalStateException("late failure");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/late-failure");
        request.setAttribute("requestId", "req-late-1");
        Logger logger = (Logger) LoggerFactory.getLogger(ExceptionBridgeFilter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);

        try {
            assertThatThrownBy(() -> new ExceptionBridgeFilter(resolver)
                    .doFilter(request, response, failingChain(failure))).isSameAs(failure);
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getFormattedMessage())
                    .contains("requestId=req-late-1", "method=POST", "path=/late-failure",
                            "java.lang.IllegalStateException", "ExceptionBridgeFilterTest.java:")
                    .doesNotContain("late failure");
            assertThat(events.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(events);
            events.stop();
        }

        verifyNoInteractions(resolver);
        assertThat(response.getContentAsString()).isEqualTo("already sent");
    }

    @Test
    void skipsErrorDispatchInsteadOfResolvingTheErrorEndpointAgain() {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/original");
        IllegalStateException failure = new IllegalStateException("error endpoint unavailable");

        assertThatThrownBy(() -> new ExceptionBridgeFilter(resolver)
                .doFilter(request, new MockHttpServletResponse(), failingChain(failure))).isSameAs(failure);

        verifyNoInteractions(resolver);
    }

    private static Stream<Exception> failures() {
        return Stream.of(new IOException("io"), new ServletException("servlet"),
                new IllegalStateException("runtime"));
    }

    private static FilterChain failingChain(Exception exception) {
        return (request, response) -> {
            switch (exception) {
                case IOException ioException -> throw ioException;
                case ServletException servletException -> throw servletException;
                case RuntimeException runtimeException -> throw runtimeException;
                default -> throw new AssertionError("Unexpected test exception type");
            }
        };
    }
}
