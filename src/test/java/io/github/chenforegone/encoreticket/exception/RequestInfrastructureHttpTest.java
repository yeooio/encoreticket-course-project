package io.github.chenforegone.encoreticket.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.github.chenforegone.encoreticket.common.exception.GlobalExceptionHandler;
import io.github.chenforegone.encoreticket.controller.ApiErrorController;
import io.github.chenforegone.encoreticket.mapper.UserMapper;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.filter.ExceptionBridgeFilter;
import io.github.chenforegone.encoreticket.security.filter.RedisTokenAuthenticationFilter;
import io.github.chenforegone.encoreticket.security.filter.RequestIdFilter;
import io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler;
import io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实容器中的最终摘要、ERROR 和异步响应边界。
 *
 * @author c1342
 */
@SpringBootTest(classes = RequestInfrastructureHttpTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off"})
class RequestInfrastructureHttpTest {
    @LocalServerPort int port;

    @Test
    void recordsOneFinalSummaryForSuccessSecurityErrorContainerErrorAndAsync() throws Exception {
        var events = new LinkedBlockingQueue<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        try (var client = HttpClient.newHttpClient()) {
            String[] paths = {"/movies/summary-ok?password=private", "/private-token", "/movies/summary-error", "/movies/summary-async", "/movies/summary-unhandled"};
            int[] statuses = {200, 401, 503, 202, 500};
            String[] routes = {"/movies/summary-ok", "<unmapped>", "/movies/summary-error", "/movies/summary-async", "/movies/summary-unhandled"};
            for (int i = 0; i < paths.length; i++) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + paths[i]))
                        .header("X-Request-Id", "client-private-token").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(statuses[i]);
                var event = events.poll(5, TimeUnit.SECONDS);
                assertThat(event).isNotNull();
                assertThat(event.getMDCPropertyMap()).containsEntry("requestId", response.headers().firstValue("X-Request-Id").orElseThrow());
                assertThat(event.getFormattedMessage()).contains("method=GET", "route=" + routes[i], "status=" + statuses[i], "durationMs=")
                        .doesNotContain("password", "private-token");
                if (i == 3) assertThat(response.body()).isEqualTo("no-worker-mdc");
            }
            assertThat(events).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void servesPackagedDefaultAvatarWithoutAuthentication() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/images/default-avatar.svg"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("image/svg+xml");
            assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
            assertThat(response.body()).contains("<svg").doesNotContain("<script", "http://cdn", "https://");
        }
    }

    /**
     * 复用既有安全边界，仅注入本测试端点。
     * @author c1342
     */
    @Configuration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
    })
    @Import({SecurityConfig.class,
            RequestIdFilter.class,
            ExceptionBridgeFilter.class,
            RedisTokenAuthenticationFilter.class,
            JsonAuthenticationEntryPoint.class,
            JsonAccessDeniedHandler.class,
            GlobalExceptionHandler.class,
            ApiErrorController.class, Probe.class})
    static class Application {
        @Bean
        AuthTokenStore tokens() {
            return Mockito.mock(AuthTokenStore.class);
        }

        @Bean
        UserMapper users() {
            return Mockito.mock(UserMapper.class);
        }
    }

    /**
     * 同步和异步 HTTP 探针。
     * @author c1342
     */
    @RestController
    static class Probe {
        @GetMapping("/movies/summary-ok")
        String ok() {
            return MDC.get("requestId");
        }

        @GetMapping("/movies/summary-error")
        void error(HttpServletResponse response) throws IOException {
            response.sendError(503);
        }

        @GetMapping("/movies/summary-unhandled")
        String unhandled() {
            throw new IllegalStateException("private-password");
        }

        @GetMapping("/movies/summary-async")
        Callable<ResponseEntity<String>> async() {
            return () -> ResponseEntity.accepted().body(MDC.get("requestId") == null ? "no-worker-mdc" : "unexpected-mdc");
        }
    }
}
