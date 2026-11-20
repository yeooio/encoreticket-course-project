package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.constant.RequestIdConstant;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.io.ContentReference;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 安全异常日志的公开行为测试。
 *
 * @author c1342
 */
class ExceptionLogSupportTest {

    @Test
    void recordsStructuredDetailsForEachNodeWithoutExposingSqlOrJson() {
        Logger log = mock(Logger.class);
        SQLException cause = new SQLException("INSERT secret-sql with secret-value", "23000", 1062);
        InvalidRequestException exception = new InvalidRequestException(ErrorCode.INVALID_ARGUMENT, cause);
        exception.addSuppressed(new StreamReadException(null, "secret-json-message",
                new TokenStreamLocation(ContentReference.rawReference("secret-json-source"), 12L, 2, 4)));

        ExceptionLogSupport.logOnce(log, request(), "Request failed", exception);

        assertThat(loggedMessage(log))
                .contains("code=INVALID_ARGUMENT", "reason=请求参数不合法",
                        "Caused by: java.sql.SQLException, sqlState=23000, vendorCode=1062",
                        "Suppressed: tools.jackson.core.exc.StreamReadException, line=2, column=4")
                .doesNotContain("secret-sql", "secret-value", "secret-json-message", "secret-json-source");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Stored authentication session is invalid",
            "Could not serialize authentication session",
            "User role data is inconsistent",
            "token must not be blank",
            "SHA-256 is not available",
            "userId must not be blank"
    })
    void omitsPreviouslyApprovedMessagesWhilePreservingFailureLocations(String message) {
        Logger log = mock(Logger.class);
        IllegalStateException exception = new IllegalStateException(message,
                new IllegalArgumentException("password=secret-password"));
        exception.setStackTrace(new StackTraceElement[]{frame("TokenStore", "read", 23)});

        ExceptionLogSupport.logOnce(log, request(), "Request failed", exception);

        assertThat(loggedMessage(log)).contains("java.lang.IllegalStateException",
                        "TokenStore.read(TokenStore.java:23)", "Caused by: java.lang.IllegalArgumentException")
                .doesNotContain(message, "reason=", "secret-password");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Stored authentication session is invalid: secret-token",
            "Stored authentication session is invalid\nsecret-token",
            "User role data is inconsistent secret-user",
            "unreviewed-message"
    })
    void rejectsDynamicSuffixesAndUnknownMessagesEvenForTheSameExceptionType(String message) {
        Logger log = mock(Logger.class);

        ExceptionLogSupport.logOnce(log, request(), "Request failed", new IllegalStateException(message));

        assertThat(loggedMessage(log)).contains("java.lang.IllegalStateException")
                .doesNotContain(message, "reason=");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "secret-sql-state", "hy000", "230000", "23_00", "HY00\n"})
    void omitsMissingOrMalformedSqlStatesWhileKeepingTheNumericErrorCode(String sqlState) {
        Logger log = mock(Logger.class);

        ExceptionLogSupport.logOnce(log, request(), "Database failed",
                new SQLException("secret-sql", sqlState, 1062));

        assertThat(loggedMessage(log)).contains("java.sql.SQLException", "vendorCode=1062")
                .doesNotContain("sqlState=", "secret-sql");
    }

    @Test
    void omitsUnavailableJsonLocationsWithoutRenderingSourceContent() {
        Logger log = mock(Logger.class);
        StreamReadException exception = new StreamReadException("secret-no-location");
        exception.addSuppressed(new StreamReadException(null, "secret-unknown-location", TokenStreamLocation.NA));
        exception.addSuppressed(new StreamReadException(null, "secret-zero-location",
                new TokenStreamLocation(ContentReference.rawReference("secret-source"), -1L, 0, 0)));

        ExceptionLogSupport.logOnce(log, request(), "JSON failed", exception);

        assertThat(loggedMessage(log)).contains("tools.jackson.core.exc.StreamReadException")
                .doesNotContain("line=", "column=", "secret-no-location", "secret-unknown-location",
                        "secret-zero-location", "secret-source");
    }

    @Test
    void usesTheBusinessCodeDefaultInsteadOfAnUnreviewedCustomMessage() {
        Logger log = mock(Logger.class);

        ExceptionLogSupport.logOnce(log, request(), "Request failed", new CustomMessageException());

        assertThat(loggedMessage(log)).contains("code=INVALID_ARGUMENT", "reason=请求参数不合法")
                .doesNotContain("secret-business-message");
    }

    @Test
    void marksOmittedFramesEvenWhenTheTotalTextStillFits() {
        Logger log = mock(Logger.class);
        IllegalStateException exception = new IllegalStateException();
        exception.setStackTrace(IntStream.range(0, 40)
                .mapToObj(line -> frame("TicketService", "read", line))
                .toArray(StackTraceElement[]::new));

        ExceptionLogSupport.logOnce(log, request(), "Request failed", exception);

        String message = loggedMessage(log);
        assertThat(message).contains("[stack frames truncated]").doesNotContain("[diagnostic truncated]");
        assertThat(message.length()).isLessThan(16_384);
    }

    @Test
    void preservesDiagnosticFramesWithoutLoggingExceptionMessagesOrRequestSecrets() {
        Logger log = mock(Logger.class);
        MockHttpServletRequest request = request();
        request.setRequestURI("/error");
        request.setMethod("GET");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/tickets/42");
        request.setAttribute(RequestDispatcher.ERROR_METHOD, "POST");
        request.addHeader("Authorization", "Bearer secret-token");
        request.setQueryString("password=secret-query");
        request.setContent("secret-body".getBytes());
        IllegalArgumentException cause = new IllegalArgumentException("password=secret-password");
        cause.setStackTrace(new StackTraceElement[]{frame("RedisStore", "read", 23)});
        IllegalStateException exception = new IllegalStateException("SELECT secret-sql FROM users", cause);
        exception.setStackTrace(new StackTraceElement[]{frame("TicketService", "load", 42)});
        UnsupportedOperationException suppressed = new UnsupportedOperationException("secret-suppressed");
        suppressed.setStackTrace(new StackTraceElement[]{frame("Connection", "close", 7)});
        exception.addSuppressed(suppressed);

        ExceptionLogSupport.logOnce(log, request, "Request failed", exception);

        String message = loggedMessage(log);
        assertThat(message).contains("requestId=req-test", "method=POST", "path=/tickets/42",
                "java.lang.IllegalStateException", "TicketService.load(TicketService.java:42)",
                "Caused by: java.lang.IllegalArgumentException", "RedisStore.read(RedisStore.java:23)",
                "Suppressed: java.lang.UnsupportedOperationException", "Connection.close(Connection.java:7)");
        assertThat(message).doesNotContain("secret-token", "secret-query", "secret-body",
                "secret-password", "secret-sql", "secret-suppressed", "path=/error");
    }

    @Test
    void deduplicatesTheSameFailureGraphWithinOneRequestOnly() {
        Logger log = mock(Logger.class);
        MockHttpServletRequest request = request();
        IllegalStateException cause = new IllegalStateException("private cause");
        IllegalArgumentException exception = new IllegalArgumentException(cause);

        ExceptionLogSupport.logOnce(log, request, "Advice failed", exception);
        ExceptionLogSupport.logOnce(log, request, "Error dispatch failed", exception);
        ExceptionLogSupport.logOnce(log, request, "Cause reached boundary", cause);
        ExceptionLogSupport.logOnce(log, request(), "Another request failed", exception);

        verify(log, times(2)).error(org.mockito.ArgumentMatchers.anyString());
        verifyNoMoreInteractions(log);
    }

    @Test
    void recordsADifferentResolverFailureOnTheSameRequest() {
        Logger log = mock(Logger.class);
        MockHttpServletRequest request = request();

        ExceptionLogSupport.logOnce(log, request, "Original failure", new IllegalStateException());
        ExceptionLogSupport.logOnce(log, request, "Resolver failure", new IllegalArgumentException());

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(log, times(2)).error(messages.capture());
        assertThat(messages.getAllValues().get(0)).contains("Original failure", "req-test");
        assertThat(messages.getAllValues().get(1)).contains("Resolver failure", "req-test");
    }

    @Test
    void recordsSafeContextOnceWhenContainerErrorHasNoException() {
        Logger log = mock(Logger.class);
        MockHttpServletRequest request = request();

        ExceptionLogSupport.logOnce(log, request, "Container error, status=503", null);
        ExceptionLogSupport.logOnce(log, request, "Container error, status=503", null);

        assertThat(loggedMessage(log)).contains("Container error, status=503", "requestId=req-test",
                "method=POST", "path=/tickets");
    }

    @Test
    void terminatesForCyclicCausesAndSuppressedFailures() {
        Logger log = mock(Logger.class);
        IllegalStateException first = new IllegalStateException("private first");
        IllegalArgumentException second = new IllegalArgumentException("private second");
        first.initCause(second);
        second.initCause(first);
        first.addSuppressed(second);
        second.addSuppressed(first);

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> ExceptionLogSupport.logOnce(log, request(), "Cyclic failure", first));

        String message = loggedMessage(log);
        assertThat(message).contains("java.lang.IllegalStateException", "java.lang.IllegalArgumentException",
                "already recorded");
        assertThat(message).doesNotContain("private first", "private second");
    }

    @Test
    void boundsDeepWideAndLargeDiagnosticOutput() {
        Logger log = mock(Logger.class);
        IllegalStateException exception = new IllegalStateException();
        Throwable cursor = exception;
        for (int index = 0; index < 100; index++) {
            IllegalStateException next = new IllegalStateException();
            cursor.initCause(next);
            cursor = next;
            next.setStackTrace(IntStream.range(0, 100)
                    .mapToObj(line -> frame("VeryLong" + "Name".repeat(100), "read", line))
                    .toArray(StackTraceElement[]::new));
            exception.addSuppressed(new IllegalArgumentException());
        }

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> ExceptionLogSupport.logOnce(log, request(), "Large failure", exception));

        String message = loggedMessage(log);
        assertThat(message.length()).isLessThanOrEqualTo(16_384);
        assertThat(message).contains("[diagnostic truncated]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"frame", "cause", "suppressed"})
    void marksOmittedContentWhenTheDiagnosticAlreadyExactlyFillsTheLimit(String omittedContent) {
        Logger log = mock(Logger.class);
        IllegalStateException exception = failureFillingLogLimit();
        switch (omittedContent) {
            case "frame" -> {
                StackTraceElement[] frames = Arrays.copyOf(exception.getStackTrace(), 27);
                frames[26] = frame("OmittedService", "read", 42);
                exception.setStackTrace(frames);
            }
            case "cause" -> exception.initCause(new IllegalArgumentException());
            case "suppressed" -> exception.addSuppressed(new IllegalArgumentException());
            default -> throw new IllegalArgumentException("Unexpected test case");
        }

        ExceptionLogSupport.logOnce(log, request(), "Request failed", exception);

        String message = loggedMessage(log);
        assertThat(message.length()).isEqualTo(16_384);
        assertThat(message).endsWith("\n[diagnostic truncated]")
                .doesNotContain("OmittedService", "java.lang.IllegalArgumentException");
    }

    @ParameterizedTest
    @CsvSource({"21, 16383, false", "22, 16384, false", "23, 16384, true"})
    void marksTruncationOnlyWhenTheCompleteDiagnosticExceedsTheLimit(
            int tailFileLength, int expectedLength, boolean truncated) {
        Logger log = mock(Logger.class);
        IllegalStateException exception = failureFillingLogLimit();
        StackTraceElement[] frames = exception.getStackTrace();
        frames[25] = new StackTraceElement("Tail", "read", "T".repeat(tailFileLength), 1);
        exception.setStackTrace(frames);

        ExceptionLogSupport.logOnce(log, request(), "Request failed", exception);

        String message = loggedMessage(log);
        assertThat(message.length()).isEqualTo(expectedLength);
        if (truncated) {
            assertThat(message).endsWith("\n[diagnostic truncated]");
        } else {
            assertThat(message).doesNotContain("[diagnostic truncated]")
                    .endsWith("Tail.read(" + "T".repeat(tailFileLength) + ":1)");
        }
    }

    @Test
    void sanitizesContextAndNeverCallsArbitraryToString() {
        Logger log = mock(Logger.class);
        MockHttpServletRequest request = request();
        request.setMethod("POST\r\nFORGED");
        request.setRequestURI("/tickets\r\nFORGED");
        request.setAttribute(RequestIdConstant.ATTRIBUTE_NAME, new Object() {
            @Override
            public String toString() {
                throw new AssertionError("Must not render arbitrary request attributes");
            }
        });

        assertThatCode(() -> ExceptionLogSupport.logOnce(log, request, "Failure\r\nFORGED",
                new IllegalStateException())).doesNotThrowAnyException();

        String firstLine = loggedMessage(log).lines().findFirst().orElseThrow();
        assertThat(firstLine).contains("Failure??FORGED", "requestId=-", "method=POST??FORGED",
                "path=/tickets??FORGED");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/tickets");
        request.setAttribute(RequestIdConstant.ATTRIBUTE_NAME, "req-test");
        return request;
    }

    private static IllegalStateException failureFillingLogLimit() {
        IllegalStateException exception = new IllegalStateException();
        StackTraceElement[] frames = new StackTraceElement[26];
        Arrays.fill(frames, new StackTraceElement("C".repeat(256), "m".repeat(128), "F".repeat(256), 1));
        frames[25] = new StackTraceElement("Tail", "read", "T".repeat(22), 1);
        // 固定请求上下文及异常类型共 94 字符，25 个 650 字符栈帧加 40 字符尾帧恰好写满。
        exception.setStackTrace(frames);
        return exception;
    }

    private static StackTraceElement frame(String className, String methodName, int line) {
        return new StackTraceElement(className, methodName, className + ".java", line);
    }

    private static String loggedMessage(Logger log) {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(log).error(message.capture());
        verifyNoMoreInteractions(log);
        return message.getValue();
    }

    /**
     * 验证业务错误日志不采用未经审阅的自定义提示。
     *
     * @author c1342
     */
    private static final class CustomMessageException extends ApplicationException {
        private CustomMessageException() {
            super(ErrorCode.INVALID_ARGUMENT, "secret-business-message");
        }
    }
}
