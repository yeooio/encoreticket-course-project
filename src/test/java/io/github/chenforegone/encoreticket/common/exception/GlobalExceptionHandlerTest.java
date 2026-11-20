package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.response.ApiErrorResponse;
import io.github.chenforegone.encoreticket.model.dto.MovieQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.RegisterRequest;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.metadata.ConstraintDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        MockHttpServletRequest mockRequest = new MockHttpServletRequest("POST", "/tickets");
        mockRequest.setAttribute("requestId", "req-test-1");
        request = mockRequest;
        mockMvc = MockMvcBuilders.standaloneSetup(new ContractProbeController())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void unrelatedQueryTimeoutRemainsInternalError() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/contract-probe/query-timeout"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void mapsConflictExceptionTo409AndKeepsBusinessCode() {
        ResponseEntity<ApiErrorResponse> response = handler.handleApplicationException(
                new ConflictException(ErrorCode.RESOURCE_CONFLICT), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("req-test-1");
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                "RESOURCE_CONFLICT", "资源已存在或状态发生冲突", null, "req-test-1"));
    }

    @Test
    void mapsResourceNotFoundExceptionTo404() {
        ResponseEntity<ApiErrorResponse> response = handler.handleApplicationException(
                new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void mapsBusinessRuleExceptionTo422() {
        ResponseEntity<ApiErrorResponse> response = handler.handleApplicationException(
                new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("BUSINESS_RULE_VIOLATION");
    }

    @Test
    void mapsMalformedApplicationInputTo400() {
        ResponseEntity<ApiErrorResponse> response = handler.handleApplicationException(
                new InvalidRequestException(ErrorCode.INVALID_ARGUMENT), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void hidesUnexpectedExceptionDetailsFromClient() throws Exception {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpectedException(
                new IllegalStateException("database password=secret"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                "INTERNAL_ERROR", "服务器内部错误", null, "req-test-1"));
    }

    @Test
    void hidesUnverifiedValidationMessageEvenWhenErrorCodeLooksLikeAConstraint() throws Exception {
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(new Object(), "request");
        errors.addError(new FieldError("request", "page", "secret-rejected-value", false,
                new String[]{"Min"}, null, "Framework detail secret-rejected-value"));

        ResponseEntity<ApiErrorResponse> response = handler.handleBindingException(
                new BindException(errors), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                "INVALID_ARGUMENT", "请求参数不合法", null, "req-test-1"));
    }

    @Test
    void usesDefaultMessageForGlobalValidationError() throws Exception {
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(new Object(), "request");
        errors.addError(new ObjectError("request", "secret-global-validation-detail"));

        ResponseEntity<ApiErrorResponse> response = handler.handleBindingException(
                new BindException(errors), request);

        assertThat(response.getBody().message()).isEqualTo("请求参数不合法");
    }

    @ParameterizedTest
    @ValueSource(strings = {"filters[secret-map-key]", "items[0].page", "page\nsecret"})
    void doesNotEchoUnreviewedDynamicFieldPaths(String field) throws Exception {
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(new Object(), "request");
        errors.addError(new FieldError("request", field, "secret-rejected-value", true,
                null, null, "secret-binding-detail"));

        ResponseEntity<ApiErrorResponse> response = handler.handleBindingException(
                new BindException(errors), request);

        assertThat(response.getBody().message()).isEqualTo("请求参数不合法");
    }

    @ParameterizedTest
    @CsvSource({
            "页码必须大于等于 1, secret-runtime-template",
            "页码不能为 ${validatedValue}, 页码不能为 ${validatedValue}",
            "{jakarta.validation.constraints.Min.message}, {jakarta.validation.constraints.Min.message}"
    })
    void rejectsRuntimeAndInterpolatedConstraintTemplates(String declaredTemplate, String runtimeTemplate)
            throws Exception {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        ConstraintDescriptor<?> descriptor = mock(ConstraintDescriptor.class);
        doReturn(MovieQueryRequest.class).when(violation).getRootBeanClass();
        doReturn(descriptor).when(violation).getConstraintDescriptor();
        when(descriptor.getMessageTemplate()).thenReturn(declaredTemplate);
        when(violation.getMessageTemplate()).thenReturn(runtimeTemplate);
        FieldError error = new FieldError("request", "page", "secret-interpolated-value");
        error.wrap(violation);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(new Object(), "request");
        errors.addError(error);

        ResponseEntity<ApiErrorResponse> response = handler.handleBindingException(
                new BindException(errors), request);

        assertThat(response.getBody().message()).isEqualTo("请求参数不合法");
    }

    @Test
    void keepsReviewedPasswordConstraintWithoutEchoingPassword() throws Exception {
        mockMvc.perform(post("/contract-probe/register")
                        .requestAttr("requestId", "req-validation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"validuser","password":"short","confirmPassword":"short",
                                 "nickname":"测试用户"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "req-validation-1"))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("password: 密码长度必须为 8-64 位，且必须同时包含字母和数字"));
    }

    @Test
    void propagatesUnexpectedFailureDuringErrorDispatch() {
        MockHttpServletRequest errorRequest = new MockHttpServletRequest("POST", "/error");
        errorRequest.setDispatcherType(DispatcherType.ERROR);
        IllegalStateException failure = new IllegalStateException("error response failed");

        assertThatThrownBy(() -> handler.handleUnexpectedException(failure, errorRequest))
                .isSameAs(failure);
    }

    @Test
    void doesNotConstructAnotherBusinessResponseDuringErrorDispatch() {
        MockHttpServletRequest errorRequest = new MockHttpServletRequest("POST", "/error");
        errorRequest.setDispatcherType(DispatcherType.ERROR);
        ConflictException failure = new ConflictException(ErrorCode.RESOURCE_CONFLICT);

        assertThatThrownBy(() -> handler.handleApplicationException(failure, errorRequest))
                .isSameAs(failure);
    }

    @Test
    void resolvesAdviceThroughMockMvcForMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/contract-probe/read-only")
                        .requestAttr("requestId", "req-mvc-1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(header().string("X-Request-Id", "req-mvc-1"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void resolvesAdviceThroughMockMvcForMalformedBody() throws Exception {
        mockMvc.perform(post("/contract-probe/json")
                        .requestAttr("requestId", "req-mvc-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "req-mvc-2"))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void resolvesAdviceThroughMockMvcForControllerException() throws Exception {
        mockMvc.perform(post("/contract-probe/not-found")
                        .requestAttr("requestId", "req-mvc-3"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "req-mvc-3"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @ParameterizedTest
    @CsvSource({
            "/missing-mvc-route, 404, RESOURCE_NOT_FOUND, 请求的资源不存在",
            "/contract-probe/json, 415, UNSUPPORTED_MEDIA_TYPE, 不支持当前请求媒体类型",
            "/contract-probe/duplicate, 409, RESOURCE_CONFLICT, 资源已存在或状态发生冲突",
            "/contract-probe/integrity, 500, INTERNAL_ERROR, 服务器内部错误"
    })
    void httpErrorsKeepSafeContract(String path,int httpStatus,String code,String message) throws Exception {
        var result=mockMvc.perform(post(path)
                        .requestAttr("requestId","req-http-contract")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("password=secret-request"))
                .andExpect(status().is(httpStatus))
                .andExpect(header().string("X-Request-Id","req-http-contract"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.requestId").value("req-http-contract"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("secret-request","secret-database","SELECT","SQLException","stackTrace");
        if (httpStatus==404) {
            assertThat(result.getResolvedException())
                    .isInstanceOf(org.springframework.web.servlet.NoHandlerFoundException.class);
        }
    }

    /**
     * HTTP 错误契约探针，不依赖业务 Service。
     * @author c1342
     */
    @RestController
    @RequestMapping("/contract-probe")
    static class ContractProbeController {

        @GetMapping("/query-timeout")
        String queryTimeout() {
            throw new org.springframework.dao.QueryTimeoutException("private-sql");
        }

        @GetMapping("/read-only")
        void readOnly(HttpServletResponse response) {
            response.setStatus(HttpStatus.OK.value());
        }

        @PostMapping(value = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        void json(@RequestBody Object body) {
        }

        @PostMapping("/register")
        void register(@Valid @RequestBody RegisterRequest body) {
        }

        @PostMapping("/duplicate")
        void duplicate() {
            throw new org.springframework.dao.DuplicateKeyException("SELECT password=secret-database",
                    new java.sql.SQLException("secret-database","23000",1062));
        }

        @PostMapping("/integrity")
        void integrity() {
            throw new org.springframework.dao.DataIntegrityViolationException("SELECT password=secret-database",
                    new java.sql.SQLException("secret-database","23000",1048));
        }

        @PostMapping("/not-found")
        void notFound() {
            throw new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
