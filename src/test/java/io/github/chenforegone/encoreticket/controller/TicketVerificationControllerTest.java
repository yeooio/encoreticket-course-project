package io.github.chenforegone.encoreticket.controller;

import io.github.chenforegone.encoreticket.controller.admin.AdminTicketVerificationController;

import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.service.TicketVerificationService;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 注入 String 认证主体的权限与严格 HTTP 契约；真实 Bearer 链路见专用组合测试。
 *
 * @author c1342
 */
@WebMvcTest(AdminTicketVerificationController.class)
@Import({SecurityConfig.class,
        io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint.class,
        io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler.class})
class TicketVerificationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean TicketVerificationService service;
    @MockitoBean AuthTokenStore tokens;
    @MockitoBean io.github.chenforegone.encoreticket.mapper.UserMapper users;

    private org.springframework.test.web.servlet.request.RequestPostProcessor staff(String role) {
        return authentication(new UsernamePasswordAuthenticationToken("7",null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }

    @Test
    void anonymousAndAdminCannotVerifyAndStaffCannotReadAudit() throws Exception {
        mvc.perform(post("/ticket-verifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/ticket-verifications").with(staff("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/ticket-verifications").with(staff("CHECKIN_STAFF"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void forwardsTrustedOperatorAndCaseSensitivePayload() throws Exception {
        mvc.perform(post("/ticket-verifications").with(staff("CHECKIN_STAFF"))
                .header("Idempotency-Key","Key_A").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketCode\":\"AbC\",\"screeningId\":\"3\"}"))
                .andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"));
        verify(service).verify(eq(7L),eq("Key_A"),argThat(q -> q.getTicketCode().equals("AbC") && q.validatedScreeningId()==3));
    }

    @Test
    void rejectsUnknownFieldsNumericStringsMissingHeaderAndInvalidIds() throws Exception {
        for (String body:List.of("{\"ticketCode\":\"Code\",\"screeningId\":\"3\",\"operatorId\":\"7\"}",
                "{\"ticketCode\":12,\"screeningId\":\"3\"}","{\"ticketCode\":\"Code\",\"screeningId\":3}")) {
            mvc.perform(post("/ticket-verifications").with(staff("CHECKIN_STAFF"))
                    .header("Idempotency-Key","key").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        mvc.perform(post("/ticket-verifications").with(staff("CHECKIN_STAFF"))
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void businessErrorsKeepStatusAndDoNotExposeFirstOperator() throws Exception {
        when(service.verify(anyLong(),anyString(),any())).thenThrow(new io.github.chenforegone.encoreticket.common.exception.TicketVerificationException(
                io.github.chenforegone.encoreticket.common.error.ErrorCode.TICKET_ALREADY_USED));
        mvc.perform(post("/ticket-verifications").with(staff("CHECKIN_STAFF"))
                .header("Idempotency-Key","key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketCode\":\"Code\",\"screeningId\":\"3\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_ALREADY_USED"))
                .andExpect(jsonPath("$.message").value("票券已核销")).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(header().exists("X-Request-Id"));
        when(service.list(any())).thenThrow(new io.github.chenforegone.encoreticket.common.exception.TicketVerificationException(
                io.github.chenforegone.encoreticket.common.error.ErrorCode.INTERNAL_ERROR));
        mvc.perform(get("/admin/ticket-verifications").with(staff("ADMIN")))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void adminEmptyPageAndInvalidQueries() throws Exception {
        when(service.list(any())).thenReturn(new PageResponse<>(List.of(),1,20,0));
        mvc.perform(get("/admin/ticket-verifications").with(staff("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        for (String query:List.of("pageSize=101","screeningId=01","operatorId=9223372036854775808",
                "sort=id","page=1&page=2","verifiedAtFrom=2026-10-19T12:00:00Z&verifiedAtTo=2026-10-19T11:00:00Z")) {
            mvc.perform(get("/admin/ticket-verifications?"+query).with(staff("ADMIN")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
    }
}
