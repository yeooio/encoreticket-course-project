package io.github.chenforegone.encoreticket.controller;

import io.github.chenforegone.encoreticket.common.constant.RequestIdConstant;
import io.github.chenforegone.encoreticket.common.response.ApiErrorResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 容器错误出口的状态映射、追踪信息及 MVC 响应契约测试。
 *
 * @author c1342
 */
class ApiErrorControllerTest {

    private final ApiErrorController controller = new ApiErrorController();

    @ParameterizedTest
    @CsvSource({
            "400,INVALID_ARGUMENT", "401,UNAUTHORIZED", "403,FORBIDDEN",
            "404,RESOURCE_NOT_FOUND", "405,METHOD_NOT_ALLOWED", "409,RESOURCE_CONFLICT",
            "415,UNSUPPORTED_MEDIA_TYPE", "422,BUSINESS_RULE_VIOLATION",
            "406,HTTP_CLIENT_ERROR", "413,HTTP_CLIENT_ERROR", "429,HTTP_CLIENT_ERROR",
            "499,HTTP_CLIENT_ERROR", "500,INTERNAL_ERROR", "503,INTERNAL_ERROR",
            "599,INTERNAL_ERROR"
    })
    void preservesContainerStatusAndMapsItsErrorCode(int statusCode, String code) {
        MockHttpServletRequest request = errorRequest(statusCode);
        request.setAttribute(RequestIdConstant.ATTRIBUTE_NAME, "req-container-1");

        ResponseEntity<ApiErrorResponse> result = controller.handleError(request, new MockHttpServletResponse());

        assertThat(result.getStatusCode().value()).isEqualTo(statusCode);
        assertThat(result.getBody().code()).isEqualTo(code);
        assertThat(result.getBody().data()).isNull();
        assertThat(result.getBody().requestId()).isEqualTo("req-container-1");
        assertThat(result.getHeaders().getFirst(RequestIdConstant.RESPONSE_HEADER_NAME))
                .isEqualTo("req-container-1");
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @ParameterizedTest
    @MethodSource("invalidStatuses")
    void defaultsInvalidOrMissingErrorStatusTo500(Object statusAttribute) {
        MockHttpServletRequest request = errorRequest(statusAttribute);

        ResponseEntity<ApiErrorResponse> result = controller.handleError(request, new MockHttpServletResponse());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }

    private static Stream<Arguments> invalidStatuses() {
        return Stream.of(Arguments.of((Object) null), Arguments.of("500"), Arguments.of(0),
                Arguments.of(200), Arguments.of(399), Arguments.of(600));
    }

    @Test
    void generatesMissingRequestIdAndReturnsTheSameValueInHeaderAndBody() {
        MockHttpServletRequest request = errorRequest(500);

        ResponseEntity<ApiErrorResponse> result = controller.handleError(request, new MockHttpServletResponse());

        String requestId = result.getBody().requestId();
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
        assertThat(request.getAttribute(RequestIdConstant.ATTRIBUTE_NAME)).isEqualTo(requestId);
        assertThat(result.getHeaders().getFirst(RequestIdConstant.RESPONSE_HEADER_NAME)).isEqualTo(requestId);
    }

    @Test
    void doesNotExposeContainerOrExceptionMessages() {
        MockHttpServletRequest request = errorRequest(500);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "password=container-secret");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,
                new IllegalStateException("Bearer exception-secret"));

        ResponseEntity<ApiErrorResponse> result = controller.handleError(request, new MockHttpServletResponse());

        assertThat(result.getBody().message()).isEqualTo("服务器内部错误");
    }

    @Test
    void ordinaryRequestUses404EvenIfErrorAttributesArePresent() {
        MockHttpServletRequest request = errorRequest(500);
        request.setDispatcherType(DispatcherType.REQUEST);

        ResponseEntity<ApiErrorResponse> result = controller.handleError(request, new MockHttpServletResponse());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void doesNotReplaceACommittedResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(503);
        response.getWriter().write("already sent");
        response.flushBuffer();

        ResponseEntity<ApiErrorResponse> result = controller.handleError(errorRequest(500), response);

        assertThat(result).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEqualTo("already sent");
        assertThat(response.getHeader(RequestIdConstant.RESPONSE_HEADER_NAME)).isNull();
    }

    @ParameterizedTest
    @CsvSource({"POST", "PATCH"})
    void returnsJsonForDifferentOriginalMethodsAndHtmlAccept(String method) throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(request(HttpMethod.valueOf(method), "/error")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            return request;
                        })
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 429)
                        .requestAttr(RequestIdConstant.ATTRIBUTE_NAME, "req-html-1")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(header().string(RequestIdConstant.RESPONSE_HEADER_NAME, "req-html-1"))
                .andExpect(jsonPath("$.code").value("HTTP_CLIENT_ERROR"))
                .andExpect(jsonPath("$.message").value("请求无法处理"))
                .andExpect(jsonPath("$.requestId").value("req-html-1"));
    }

    private static MockHttpServletRequest errorRequest(Object statusAttribute) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, statusAttribute);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/original-resource");
        return request;
    }
}
