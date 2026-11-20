package io.github.chenforegone.encoreticket.controller;

import io.github.chenforegone.encoreticket.controller.user.UserOrderController;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.token.AuthTokenSession;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.service.RefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 退款用户 HTTP 契约。
 * @author c1342
 */
@WebMvcTest({UserOrderController.class, io.github.chenforegone.encoreticket.controller.admin.AdminRefundController.class})
@Import({SecurityConfig.class,
        io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint.class,
        io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler.class})
class RefundControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean RefundService refunds;
    @MockitoBean AuthTokenStore tokens;
    @MockitoBean io.github.chenforegone.encoreticket.mapper.UserMapper users;

    @org.junit.jupiter.api.BeforeEach
    void activeAccounts() {
        when(users.selectById("20")).thenReturn(io.github.chenforegone.encoreticket.model.entity.User.register("test", "hash", "测试", "avatar"));
        when(users.selectById("99")).thenReturn(io.github.chenforegone.encoreticket.model.entity.User.register("admin", "hash", "测试", "avatar"));
    }

    @Test
    void userCanApplyForFullRefundAndQueryIt() throws Exception {
        when(tokens.findSessionByToken("valid-token")).thenReturn(new AuthTokenSession("20", List.of("USER"), 0L));
        var now = java.time.Instant.parse("2026-10-20T05:00:00Z");
        var refund = new io.github.chenforegone.encoreticket.model.vo.RefundVO(
                "50", "R50", "10", "30", "88.00", "CNY", "PENDING", "USER", now, now);
        when(refunds.apply(10L, 20L, "apply-1")).thenReturn(refund);
        when(refunds.byOrder(10L, 20L)).thenReturn(new io.github.chenforegone.encoreticket.model.vo.OrderRefundsVO(
                "10", "REFUNDING", List.of(refund)));
        mvc.perform(post("/orders/10/refunds").header("Authorization", "Bearer valid-token")
                        .header("Idempotency-Key", "apply-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.amount").value("88.00"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mvc.perform(get("/orders/10/refunds").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].refundId").value("50"));
    }

    @Test
    void adminProcessRejectsManualSuccessAndUserCannotManage() throws Exception {
        when(tokens.findSessionByToken("valid-token")).thenReturn(new AuthTokenSession("20", List.of("USER"), 0L));
        mvc.perform(get("/admin/refunds").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
        when(tokens.findSessionByToken("valid-token")).thenReturn(new AuthTokenSession("20", List.of("ADMIN"), 0L));
        mvc.perform(post("/admin/refunds/50/process").header("Authorization", "Bearer valid-token")
                        .header("Idempotency-Key", "process-1").contentType("application/json")
                        .content("{\"action\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void applicationCannotSpecifyAmountOrAccount() throws Exception {
        when(tokens.findSessionByToken("valid-token")).thenReturn(new AuthTokenSession("20", List.of("USER"), 0L));
        mvc.perform(post("/orders/10/refunds").header("Authorization", "Bearer valid-token")
                        .header("Idempotency-Key", "apply-1").contentType("application/json")
                        .content("{\"amount\":\"1.00\",\"account\":\"other\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminGetRejectsBodiesAndUnknownOrRepeatedQueryWithoutCallingService() throws Exception {
        when(tokens.findSessionByToken("valid-token")).thenReturn(new AuthTokenSession("99", List.of("ADMIN"), 0L));
        for (String path : List.of("/admin/refunds", "/admin/refunds/50")) {
            mvc.perform(get(path).header("Authorization", "Bearer valid-token")
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
            mvc.perform(get(path).header("Authorization", "Bearer valid-token").param("unknown", "1"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
            mvc.perform(get(path).header("Authorization", "Bearer valid-token").param("page", "1", "2"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(refunds);
    }

    @Test
    void applicationRequiresIdempotencyKey() throws Exception {
        when(tokens.findSessionByToken("valid-token")).thenReturn(new AuthTokenSession("20", List.of("USER"), 0L));
        mvc.perform(post("/orders/10/refunds").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
}
