package io.github.chenforegone.encoreticket.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 Logback 调用验证框架异常事件的定向替换。
 *
 * @author c1342
 */
class FrameworkExceptionLogFilterTest {

    private static final String RESOLVER_LOGGER =
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver";
    private static final String CONTAINER_LOGGER =
            "org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]";
    private static final String SECRET = "test-secret-password-token-sql";

    private LoggerContext context;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        FrameworkExceptionLogFilter filter = new FrameworkExceptionLogFilter();
        filter.setContext(context);
        filter.start();
        context.addTurboFilter(filter);
    }

    @AfterEach
    void tearDown() {
        context.stop();
    }

    @ParameterizedTest
    @CsvSource({
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver, Failure in @ExceptionHandler ",
            "org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet], Servlet.service() for servlet ",
            "org.apache.catalina.core.ContainerBase.[Tomcat].[localhost], Exception Processing [ErrorPage"
    })
    void replacesOnlyTheKnownFailurePayloadAndRetainsLoggerLevelAndFrames(String loggerName, String prefix) {
        Logger logger = context.getLogger(loggerName);
        IllegalArgumentException cause = new IllegalArgumentException(SECRET);
        cause.setStackTrace(new StackTraceElement[]{new StackTraceElement("TokenStore", "read", "TokenStore.java", 31)});
        IllegalStateException failure = new IllegalStateException(SECRET, cause);
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("FailureHandler", "handle", "FailureHandler.java", 17)});
        Marker marker = MarkerFactory.getDetachedMarker("diagnostic");

        logger.warn(marker, prefix + " " + SECRET, failure);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLoggerName()).isEqualTo(loggerName);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getMarkerList()).contains(marker);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage()).doesNotContain(SECRET)
                .contains("java.lang.IllegalStateException", "FailureHandler.handle(FailureHandler.java:17)",
                        "Caused by: java.lang.IllegalArgumentException", "TokenStore.read(TokenStore.java:31)");
    }

    @Test
    void extractsThrowableFromVarargsWithoutFormattingSensitiveArguments() {
        Logger logger = context.getLogger(RESOLVER_LOGGER);

        logger.warn("Failure in @ExceptionHandler {}", SECRET, new IllegalStateException(SECRET));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("java.lang.IllegalStateException").doesNotContain(SECRET);
        assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
    }

    @Test
    void retainsSqlDiagnosticsWhileOmittingOrdinaryExceptionMessages() {
        Logger logger = context.getLogger(CONTAINER_LOGGER);
        SQLException cause = new SQLException(SECRET, "23000", 1062);
        IllegalStateException failure = new IllegalStateException("User role data is inconsistent", cause);

        logger.error("Servlet.service() for servlet " + SECRET, failure);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLoggerName()).isEqualTo(CONTAINER_LOGGER);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage())
                .contains("java.lang.IllegalStateException", "Caused by: java.sql.SQLException",
                        "sqlState=23000", "vendorCode=1062")
                .doesNotContain(SECRET, "User role data is inconsistent", "reason=");
    }

    @Test
    void retainsUnrelatedContainerErrors() {
        Logger logger = context.getLogger(CONTAINER_LOGGER);
        IllegalStateException failure = new IllegalStateException("component startup failure");

        logger.error("Failed to start component", failure);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage()).isEqualTo("Failed to start component");
        assertThat(appender.list.getFirst().getThrowableProxy().getMessage())
                .isEqualTo("component startup failure");
    }

    @Test
    void retainsOtherLoggerEventsEvenWhenTheirMessagesMatch() {
        Logger logger = context.getLogger("example.Application");

        logger.error("Failure in @ExceptionHandler own event", new IllegalStateException("own detail"));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage())
                .isEqualTo("Failure in @ExceptionHandler own event");
        assertThat(appender.list.getFirst().getThrowableProxy().getMessage()).isEqualTo("own detail");
    }

    @Test
    void respectsDisabledLevelsWhenReplacingAnEvent() {
        Logger logger = context.getLogger(RESOLVER_LOGGER);
        logger.setLevel(Level.ERROR);

        logger.warn("Failure in @ExceptionHandler secret", new IllegalStateException(SECRET));

        assertThat(appender.list).isEmpty();
    }
}
