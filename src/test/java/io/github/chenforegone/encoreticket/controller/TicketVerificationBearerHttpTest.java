package io.github.chenforegone.encoreticket.controller;

import io.github.chenforegone.encoreticket.controller.admin.AdminTicketVerificationController;
import io.github.chenforegone.encoreticket.mapper.UserMapper;
import io.github.chenforegone.encoreticket.model.entity.User;
import io.github.chenforegone.encoreticket.model.enums.UserStatus;
import io.github.chenforegone.encoreticket.model.vo.TicketVerificationVO;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.filter.ExceptionBridgeFilter;
import io.github.chenforegone.encoreticket.security.filter.RedisTokenAuthenticationFilter;
import io.github.chenforegone.encoreticket.security.filter.RequestIdFilter;
import io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler;
import io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint;
import io.github.chenforegone.encoreticket.security.token.AuthTokenSession;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.service.TicketVerificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 Bearer 过滤器到核销控制器的身份与拒绝边界；存储依赖全部替身，不连接数据库或 Redis。
 *
 * @author c1342
 */
@WebMvcTest(AdminTicketVerificationController.class)
@Import({SecurityConfig.class, RequestIdFilter.class, ExceptionBridgeFilter.class,
        RedisTokenAuthenticationFilter.class, JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class})
class TicketVerificationBearerHttpTest {
    private static final String USER_ID = "9007199254740993";
    private static final long OPERATOR_ID = 9007199254740993L;
    private static final String TOKEN = "checkin-test-token";

    @Autowired private MockMvc mvc;
    @MockitoBean private TicketVerificationService service;
    @MockitoBean private AuthTokenStore tokens;
    @MockitoBean private UserMapper users;

    @Test
    void staffBearerReachesControllerWithTrustedStringPrincipal() throws Exception {
        session("CHECKIN_STAFF", 3L, UserStatus.NORMAL);
        var result = new TicketVerificationVO("20001", "30001", "3", "测试电影",
                OffsetDateTime.parse("2026-10-27T20:15:00+08:00"),
                OffsetDateTime.parse("2026-10-27T22:00:00+08:00"), "1号厅", 3, 8, "USED",
                Instant.parse("2026-10-27T12:00:00Z"));
        when(service.verify(eq(OPERATOR_ID), eq("Key_A"), argThat(request ->
                request.getTicketCode().equals("AbC") && request.validatedScreeningId() == 3L)))
                .thenReturn(result);

        mvc.perform(request().header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data.verificationId").value("20001"))
                .andExpect(jsonPath("$.data.ticketId").value("30001"))
                .andExpect(jsonPath("$.data.screeningId").value("3"))
                .andExpect(jsonPath("$.data.status").value("USED"))
                .andExpect(jsonPath("$.data.operatorId").doesNotExist());

        verify(tokens).findSessionByToken(TOKEN);
        verify(users).selectById(USER_ID);
        verify(service).verify(eq(OPERATOR_ID), eq("Key_A"), argThat(request ->
                request.getTicketCode().equals("AbC") && request.validatedScreeningId() == 3L));
        verifyNoMoreInteractions(tokens, users, service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void nonStaffBearerIsForbiddenWithoutBusinessWrites(String role) throws Exception {
        session(role, 3L, UserStatus.NORMAL);

        expectError(mvc.perform(request().header("Authorization", "Bearer " + TOKEN)), 403, "FORBIDDEN");

        verify(tokens).findSessionByToken(TOKEN);
        verify(users).selectById(USER_ID);
        verifyNoMoreInteractions(tokens, users);
        verifyNoInteractions(service);
    }

    @Test
    void unknownOrExpiredBearerIsUnauthorizedWithoutBusinessWrites() throws Exception {
        expectError(mvc.perform(request().header("Authorization", "Bearer " + TOKEN)), 401, "UNAUTHORIZED");

        verify(tokens).findSessionByToken(TOKEN);
        verifyNoMoreInteractions(tokens);
        verifyNoInteractions(users, service);
    }

    @Test
    void missingBearerIsUnauthorizedWithoutStorageAccess() throws Exception {
        expectError(mvc.perform(request()), 401, "UNAUTHORIZED");

        verifyNoInteractions(tokens, users, service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic abc", "Bearer", "Bearer a b"})
    void malformedAuthorizationIsUnauthorizedWithoutStorageAccess(String authorization) throws Exception {
        expectError(mvc.perform(request().header("Authorization", authorization)), 401, "UNAUTHORIZED");

        verifyNoInteractions(tokens, users, service);
    }

    @Test
    void staleStaffSessionIsUnauthorizedWithoutBusinessWrites() throws Exception {
        session("CHECKIN_STAFF", 2L, UserStatus.NORMAL);

        expectError(mvc.perform(request().header("Authorization", "Bearer " + TOKEN)), 401, "UNAUTHORIZED");

        verify(tokens).findSessionByToken(TOKEN);
        verify(users).selectById(USER_ID);
        verifyNoMoreInteractions(tokens, users);
        verifyNoInteractions(service);
    }

    @Test
    void disabledStaffIsUnauthorizedWithoutBusinessWrites() throws Exception {
        session("CHECKIN_STAFF", 3L, UserStatus.DISABLED);

        expectError(mvc.perform(request().header("Authorization", "Bearer " + TOKEN)), 401, "UNAUTHORIZED");

        verify(tokens).findSessionByToken(TOKEN);
        verify(users).selectById(USER_ID);
        verifyNoMoreInteractions(tokens, users);
        verifyNoInteractions(service);
    }

    @Test
    void staffCannotForgeOperatorInRequestBody() throws Exception {
        session("CHECKIN_STAFF", 3L, UserStatus.NORMAL);

        expectError(mvc.perform(request().header("Authorization", "Bearer " + TOKEN)
                .content("{\"ticketCode\":\"AbC\",\"screeningId\":\"3\",\"operatorId\":\"7\"}")),
                400, "INVALID_ARGUMENT");

        verify(tokens).findSessionByToken(TOKEN);
        verify(users).selectById(USER_ID);
        verifyNoMoreInteractions(tokens, users);
        verifyNoInteractions(service);
    }

    private void session(String role, Long authVersion, UserStatus status) {
        var user = User.register("checkin-test", "unused-hash", "检票员", "avatar");
        user.setId(OPERATOR_ID);
        user.setAuthVersion(3L);
        user.setStatus(status);
        when(tokens.findSessionByToken(TOKEN))
                .thenReturn(new AuthTokenSession(USER_ID, List.of(role), authVersion));
        when(users.selectById(USER_ID)).thenReturn(user);
    }

    private MockHttpServletRequestBuilder request() {
        return post("/ticket-verifications")
                .header("Idempotency-Key", "Key_A")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketCode\":\"AbC\",\"screeningId\":\"3\"}");
    }

    private void expectError(ResultActions action, int httpStatus, String code) throws Exception {
        var response = action.andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn().getResponse();
        String requestId = response.getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();
        action.andExpect(jsonPath("$.requestId").value(requestId));
        assertThat(response.getContentAsString()).doesNotContain(TOKEN, "AbC", "Key_A", USER_ID);
    }
}
