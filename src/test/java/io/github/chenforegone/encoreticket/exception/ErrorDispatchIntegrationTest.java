package io.github.chenforegone.encoreticket.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.github.chenforegone.encoreticket.common.constant.RequestIdConstant;
import io.github.chenforegone.encoreticket.common.exception.GlobalExceptionHandler;
import io.github.chenforegone.encoreticket.common.response.ApiErrorResponse;
import io.github.chenforegone.encoreticket.controller.ApiErrorController;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.filter.ExceptionBridgeFilter;
import io.github.chenforegone.encoreticket.security.filter.RedisTokenAuthenticationFilter;
import io.github.chenforegone.encoreticket.security.filter.RequestIdFilter;
import io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler;
import io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint;
import io.github.chenforegone.encoreticket.security.token.AuthTokenSession;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.io.ContentReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises real Tomcat ERROR dispatch with the production MVC and Security boundaries.
 *
 * @author c1342
 */
@SpringBootTest(
        classes = ErrorDispatchIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=false", "spring.main.banner-mode=off"}
)
class ErrorDispatchIntegrationTest {

    private static final String USER_TOKEN = "error-dispatch-user";
    private static final String SECRET = "test-secret-password-token-sql-94719";

    @LocalServerPort
    private int port;

    @Autowired
    private AuthTokenStore tokenStore;

    @Autowired
    private ProbeState state;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        reset(tokenStore);
        when(tokenStore.findSessionByToken(USER_TOKEN))
                .thenReturn(new AuthTokenSession("1001", List.of("USER"), 0L));
        state.reset();
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logCapture.start();
        rootLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logCapture);
        logCapture.stop();
    }

    @Test
    void redisFailureFailsClosedWithoutInvokingThePublicEndpoint() throws Exception {
        when(tokenStore.findSessionByToken("redis-failure"))
                .thenThrow(new RedisConnectionFailureException(SECRET,
                        new IllegalStateException(SECRET)));

        HttpResponse<String> response = request("GET", "/movies/business-counter", "redis-failure", "application/json");

        assertError(response, 503, "SERVICE_UNAVAILABLE");
        assertThat(state.businessCalls).hasValue(0);
        verify(tokenStore, times(1)).findSessionByToken("redis-failure");
        assertSafeDiagnosticLogs("RedisConnectionFailureException");
    }

    @Test
    void sessionFailureLogsJsonLocationAndReturnsDefaultMessageWhileOmittingOriginalMessage() throws Exception {
        StreamReadException cause = new StreamReadException(null, SECRET,
                new TokenStreamLocation(ContentReference.rawReference(SECRET), 12L, 2, 4));
        when(tokenStore.findSessionByToken("invalid-session"))
                .thenThrow(new IllegalStateException("Stored authentication session is invalid", cause));

        HttpResponse<String> response = request("GET", "/movies/business-counter", "invalid-session", "application/json");

        JsonNode body = assertError(response, 500, "INTERNAL_ERROR");
        assertThat(body.path("message").asText()).isEqualTo("服务器内部错误");
        assertThat(response.body()).doesNotContain("Stored authentication session is invalid",
                "IllegalStateException", "StreamReadException", "reason=", "line=", "column=");
        assertThat(state.businessCalls).hasValue(0);
        verify(tokenStore, times(1)).findSessionByToken("invalid-session");
        assertSafeDiagnosticLogs("StreamReadException");
        assertThat(logCapture.list)
                .filteredOn(event -> GlobalExceptionHandler.class.getName().equals(event.getLoggerName()))
                .singleElement().satisfies(event -> {
                    assertThat(event.getFormattedMessage())
                            .contains("IllegalStateException", "line=2", "column=4",
                                    "requestId=" + body.path("requestId").asText())
                            .doesNotContain("Stored authentication session is invalid", "reason=");
                    assertThat(event.getThrowableProxy()).isNull();
                });
    }

    @Test
    void pathSecurityKeepsAnonymous401AndAuthenticated403() throws Exception {
        assertError(request("GET", "/admin/probe", null, "application/json"), 401, "UNAUTHORIZED");
        assertError(request("GET", "/admin/probe", USER_TOKEN, "application/json"), 403, "FORBIDDEN");
        assertThat(state.businessCalls).hasValue(0);
    }

    @Test
    void methodSecurityKeepsAnonymous401AndAuthenticated403() throws Exception {
        assertError(request("GET", "/movies/method-secured", null, "application/json"), 401, "UNAUTHORIZED");
        assertError(request("GET", "/movies/method-secured", USER_TOKEN, "application/json"), 403, "FORBIDDEN");
        assertThat(state.businessCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PATCH"})
    void errorDispatchHandlesPostAndPatchAndRetainsTheirOriginalMethod(String method) throws Exception {
        HttpResponse<String> response = request(method, "/probe/send-error/500", USER_TOKEN, "application/json");

        assertError(response, 500, "INTERNAL_ERROR");
        // Servlet 6.1 uses GET for ERROR dispatch and exposes the original method separately.
        assertThat(state.errorMethods).containsExactly("GET");
        assertThat(state.originalErrorMethods).containsExactly(method);
        assertThat(state.errorDispatches).hasValue(1);
    }

    @Test
    void preservesUnmappedClientStatusAndSkipsRedisOnErrorDispatch() throws Exception {
        HttpResponse<String> response = request("GET", "/probe/send-error/429", USER_TOKEN, "application/json");

        assertError(response, 429, "HTTP_CLIENT_ERROR");
        assertThat(state.errorDispatches).hasValue(1);
        verify(tokenStore, times(1)).findSessionByToken(USER_TOKEN);
    }

    @Test
    void preservesRequestIdAcrossContainerErrorDispatch() throws Exception {
        HttpResponse<String> response = request("GET", "/probe/send-error/503", USER_TOKEN, "application/json");

        assertError(response, 503, "INTERNAL_ERROR");
        assertThat(response.headers().firstValue("X-Request-Id")).contains(state.originalRequestId.get());
    }

    @Test
    void createsRequestIdWhenErrorDispatchHasNone() throws Exception {
        HttpResponse<String> response = request("GET", "/probe/missing-request-id", USER_TOKEN, "application/json");

        JsonNode body = assertError(response, 500, "INTERNAL_ERROR");
        assertThat(body.path("requestId").asText()).isNotEqualTo(state.originalRequestId.get());
    }

    @Test
    void htmlAcceptStillReceivesJsonFromTheErrorController() throws Exception {
        assertError(request("GET", "/probe/send-error/500", USER_TOKEN, "text/html"), 500, "INTERNAL_ERROR");
    }

    @Test
    void directErrorRequestsRemainProtectedAndDoNotPretendToBeServerFailures() throws Exception {
        assertError(request("GET", "/error", null, "application/json"), 401, "UNAUTHORIZED");
        assertError(request("GET", "/error", USER_TOKEN, "application/json"), 404, "RESOURCE_NOT_FOUND");
        assertThat(state.errorDispatches).hasValue(0);
    }

    @Test
    void failedAdviceLetsTheNextResolverSeeTheOriginalFailureBeforeContainerFallback() throws Exception {
        HttpResponse<String> response = request("GET", "/probe/failed-advice", USER_TOKEN, "application/json");

        assertError(response, 500, "INTERNAL_ERROR");
        assertThat(state.unresolvedFailures).contains(state.originalFailure.get());
        assertThat(state.errorDispatches).hasValue(1);
        assertThat(state.adviceInvocations.get()).isBetween(1, 2);
        assertSafeDiagnosticLogs("AdviceProbeFailure");
    }

    @Test
    void failureWhileWritingTheErrorResponseDoesNotDispatchRecursively() throws Exception {
        HttpResponse<String> response = request("GET", "/probe/error-handler-failure", USER_TOKEN, "application/json");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(state.errorDispatches).hasValue(1);
        assertThat(state.errorWriteFailures).hasValue(1);
        assertThat(response.body()).doesNotContain(SECRET);
        assertSafeDiagnosticLogs("IllegalStateException");
    }

    @Test
    void containerReplacesPartialUncommittedBodyAndStaleContentLength() throws Exception {
        HttpResponse<String> response = request("GET", "/probe/partial-body", USER_TOKEN, "application/json");

        assertError(response, 500, "INTERNAL_ERROR");
        assertThat(response.body()).doesNotContain("unfinished-response");
        assertThat(response.headers().firstValue("Content-Length").orElse("")).isNotEqualTo("9000");
    }

    @Test
    void headErrorRetainsStatusAndTraceHeaderWithoutAResponseBody() throws Exception {
        HttpResponse<String> response = request("HEAD", "/probe/send-error/429", USER_TOKEN, "application/json");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.body()).isEmpty();
        assertThat(state.errorDispatches).hasValue(1);
    }

    private HttpResponse<String> request(String method, String path, String token, String accept)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", accept)
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("data").isNull()).isTrue();
        String requestId = body.path("requestId").asText();
        assertThat(requestId).isNotBlank();
        assertThat(response.headers().firstValue("X-Request-Id")).contains(requestId);
        assertThat(response.body()).doesNotContain(SECRET);
        return body;
    }

    private void assertSafeDiagnosticLogs(String exceptionType) {
        StringBuilder logs = new StringBuilder();
        for (ILoggingEvent event : logCapture.list) {
            logs.append(event.getLoggerName()).append(' ').append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                logs.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
        }
        assertThat(logs.toString()).contains(exceptionType, "requestId=", "ErrorDispatchIntegrationTest")
                .doesNotContain(SECRET);
    }

    /**
     * Uses real web infrastructure without loading database or Redis connections.
     *
     * @author c1342
     */
    @Configuration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
    })
    @Import({SecurityConfig.class, RequestIdFilter.class, ExceptionBridgeFilter.class,
            RedisTokenAuthenticationFilter.class, JsonAuthenticationEntryPoint.class,
            JsonAccessDeniedHandler.class, GlobalExceptionHandler.class, ApiErrorController.class,
            ProbeController.class, FailingAdvice.class, FailingErrorBodyAdvice.class})
    static class TestApplication implements WebMvcConfigurer {

        @Bean
        AuthTokenStore tokenStore() {
            return mock(AuthTokenStore.class);
        }

        @Bean
        io.github.chenforegone.encoreticket.mapper.UserMapper users() {
            var users = mock(io.github.chenforegone.encoreticket.mapper.UserMapper.class);
            when(users.selectById("1001")).thenReturn(io.github.chenforegone.encoreticket.model.entity.User.register("test", "hash", "测试", "avatar"));
            return users;
        }

        @Bean
        ProbeState probeState() {
            return new ProbeState();
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    if (request.getDispatcherType() == DispatcherType.ERROR) {
                        probeState().errorDispatches.incrementAndGet();
                        probeState().errorMethods.add(request.getMethod());
                        probeState().originalErrorMethods.add((String) request.getAttribute(RequestDispatcher.ERROR_METHOD));
                    }
                    return true;
                }
            });
        }

        @Override
        public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
            resolvers.add((request, response, handler, exception) -> {
                probeState().unresolvedFailures.add(exception);
                return null;
            });
        }
    }

    /**
     * Tracks observable calls across server and client threads.
     *
     * @author c1342
     */
    static class ProbeState {
        private final AtomicInteger businessCalls = new AtomicInteger();
        private final AtomicInteger errorDispatches = new AtomicInteger();
        private final AtomicInteger adviceInvocations = new AtomicInteger();
        private final AtomicInteger errorWriteFailures = new AtomicInteger();
        private final AtomicReference<String> originalRequestId = new AtomicReference<>();
        private final AtomicReference<AdviceProbeFailure> originalFailure = new AtomicReference<>();
        private final List<String> errorMethods = new CopyOnWriteArrayList<>();
        private final List<String> originalErrorMethods = new CopyOnWriteArrayList<>();
        private final List<Exception> unresolvedFailures = new CopyOnWriteArrayList<>();

        private void reset() {
            businessCalls.set(0);
            errorDispatches.set(0);
            adviceInvocations.set(0);
            errorWriteFailures.set(0);
            originalRequestId.set(null);
            originalFailure.set(null);
            errorMethods.clear();
            originalErrorMethods.clear();
            unresolvedFailures.clear();
        }
    }

    /**
     * Failure probes are available only inside this isolated test application.
     *
     * @author c1342
     */
    @RestController
    static class ProbeController {
        private final ProbeState state;

        ProbeController(ProbeState state) {
            this.state = state;
        }

        @GetMapping({"/movies/business-counter", "/admin/probe"})
        String business() {
            state.businessCalls.incrementAndGet();
            return "ok";
        }

        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/movies/method-secured")
        String methodSecured() {
            state.businessCalls.incrementAndGet();
            return "ok";
        }

        @RequestMapping("/probe/send-error/{status}")
        void sendError(@PathVariable int status, HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            state.originalRequestId.set((String) request.getAttribute(RequestIdConstant.ATTRIBUTE_NAME));
            response.sendError(status);
        }

        @GetMapping("/probe/missing-request-id")
        void missingRequestId(HttpServletRequest request, HttpServletResponse response) throws IOException {
            state.originalRequestId.set((String) request.getAttribute(RequestIdConstant.ATTRIBUTE_NAME));
            request.removeAttribute(RequestIdConstant.ATTRIBUTE_NAME);
            response.setHeader(RequestIdConstant.RESPONSE_HEADER_NAME, null);
            response.sendError(500);
        }

        @GetMapping("/probe/failed-advice")
        void failedAdvice() {
            AdviceProbeFailure failure = new AdviceProbeFailure();
            state.originalFailure.set(failure);
            throw failure;
        }

        @GetMapping("/probe/error-handler-failure")
        void errorHandlerFailure(HttpServletResponse response) throws IOException {
            response.sendError(500);
        }

        @GetMapping("/probe/partial-body")
        void partialBody(HttpServletResponse response) throws IOException {
            response.setBufferSize(16384);
            response.setContentLength(9000);
            response.getWriter().write("unfinished-response");
            response.sendError(500);
        }
    }

    /**
     * Marks an original failure that the test Advice deliberately cannot handle.
     *
     * @author c1342
     */
    static class AdviceProbeFailure extends RuntimeException {
        AdviceProbeFailure() {
            super(SECRET);
        }
    }

    /**
     * Models an exception handler whose own execution fails.
     *
     * @author c1342
     */
    @RestControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class FailingAdvice {
        private final ProbeState state;

        FailingAdvice(ProbeState state) {
            this.state = state;
        }

        @ExceptionHandler(AdviceProbeFailure.class)
        void fail(AdviceProbeFailure exception) {
            state.adviceInvocations.incrementAndGet();
            throw new IllegalStateException(SECRET);
        }
    }

    /**
     * Models a write pipeline failure after the real ErrorController returns its value.
     *
     * @author c1342
     */
    @RestControllerAdvice
    static class FailingErrorBodyAdvice implements ResponseBodyAdvice<ApiErrorResponse> {
        private final ProbeState state;

        FailingErrorBodyAdvice(ProbeState state) {
            this.state = state;
        }

        @Override
        public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
            return true;
        }

        @Override
        public ApiErrorResponse beforeBodyWrite(ApiErrorResponse body, MethodParameter returnType,
                                               MediaType selectedContentType,
                                               Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                               ServerHttpRequest request, ServerHttpResponse response) {
            HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
            if (servletRequest.getDispatcherType() == DispatcherType.ERROR
                    && "/probe/error-handler-failure".equals(servletRequest.getAttribute(RequestDispatcher.ERROR_REQUEST_URI))) {
                state.errorWriteFailures.incrementAndGet();
                throw new IllegalStateException(SECRET);
            }
            return body;
        }
    }
}
