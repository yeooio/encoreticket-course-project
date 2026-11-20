package io.github.chenforegone.encoreticket.controller;

import io.github.chenforegone.encoreticket.controller.admin.AdminPaymentController;
import io.github.chenforegone.encoreticket.controller.user.UserOrderPaymentController;

import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.model.vo.*;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.token.AuthTokenSession;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 支付查询 HTTP 响应与安全过滤器链契约测试。
 *
 * @author c1342
 */
@WebMvcTest({UserOrderPaymentController.class, AdminPaymentController.class})
@Import({SecurityConfig.class,
        io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint.class,
        io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler.class})
class PaymentQueryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PaymentService service;
    @MockitoBean AuthTokenStore tokens;
    private final Instant now = Instant.parse("2026-10-19T05:00:00Z");

    @MockitoBean io.github.chenforegone.encoreticket.mapper.UserMapper users;

    private void login(String role) {
        when(users.selectById("20")).thenReturn(io.github.chenforegone.encoreticket.model.entity.User.register("test", "hash", "测试", "avatar"));
        when(tokens.findSessionByToken("valid-token")).thenReturn(new AuthTokenSession("20", List.of(role), 0L));
    }

    private PaymentVO payment() {
        return new PaymentVO("9007199254740993", "P30", "10", "88.00", "CNY", "SUCCESS", now, now);
    }

    @Test
    void userHistoryContainsStringIdsStatusRecordsAndUtcTime() throws Exception {
        login("USER");
        when(service.listByOrder(10L, 20L)).thenReturn(new OrderPaymentsVO("10", "PAID", List.of(payment())));
        mvc.perform(get("/orders/10/payments").header("Authorization", "Bearer valid-token").param("userId", "99"))
                .andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data.orderId").value("10"))
                .andExpect(jsonPath("$.data.orderStatus").value("PAID"))
                .andExpect(jsonPath("$.data.items[0].paymentId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].amount").value("88.00"))
                .andExpect(jsonPath("$.data.items[0].createdAt").value(now.toString()))
                .andExpect(jsonPath("$.data.items[0].ticketCode").doesNotExist());
        verify(service).listByOrder(10L, 20L);
    }

    @Test
    void emptyHistoryStillContainsOrderStatus() throws Exception {
        login("USER");
        when(service.listByOrder(10L, 20L)).thenReturn(new OrderPaymentsVO("10", "PENDING_PAYMENT", List.of()));
        mvc.perform(get("/orders/10/payments").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void adminDetailSerializesOnlySafeSummaryFields() throws Exception {
        login("ADMIN");
        var order = new PaymentDetailVO.OrderSummary("10", "O10", "40", "REFUNDING", "88.00", "CNY", now);
        var refund = new PaymentDetailVO.RefundSummary("50", "R50", "88.00", "CNY", "PENDING", now, now);
        var notification = new PaymentDetailVO.NotificationSummary("SIM", "event-1", "SUCCESS", now, now);
        when(service.adminDetail(30L)).thenReturn(new PaymentDetailVO(payment(), order, refund, List.of(), List.of(notification)));
        mvc.perform(get("/admin/payments/30").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data", aMapWithSize(5)))
                .andExpect(jsonPath("$.data.order.orderId").value("10"))
                .andExpect(jsonPath("$.data.order.status").value("REFUNDING"))
                .andExpect(jsonPath("$.data.refund.refundId").value("50"))
                .andExpect(jsonPath("$.data.refund.amount").value("88.00"))
                .andExpect(jsonPath("$.data.audits").isEmpty())
                .andExpect(jsonPath("$.data.notifications[0]", aMapWithSize(5)))
                .andExpect(jsonPath("$.data.notifications[0].eventId").value("event-1"))
                .andExpect(jsonPath("$.data.notifications[0].processedAt").value(now.toString()))
                .andExpect(content().string(not(containsString("ticketCode"))))
                .andExpect(content().string(not(containsString("payloadHash"))))
                .andExpect(content().string(not(containsString("attemptId"))));
    }

    @Test
    void adminDetailWithoutRefundKeepsEmptyHistory() throws Exception {
        login("ADMIN");
        var order = new PaymentDetailVO.OrderSummary("10", "O10", "40", "PAID", "88.00", "CNY", now);
        when(service.adminDetail(30L)).thenReturn(new PaymentDetailVO(payment(), order, null, List.of(), List.of()));
        mvc.perform(get("/admin/payments/30").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refund").isEmpty())
                .andExpect(jsonPath("$.data.notifications").isEmpty());
    }

    @Test
    void queriesRequireAuthenticationAndAdminDetailRequiresAdmin() throws Exception {
        for (String path : List.of("/orders/10/payments", "/admin/payments/30")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        login("USER");
        mvc.perform(get("/admin/payments/30").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void invalidIdsAreRejectedBeforeService() throws Exception {
        login("ADMIN");
        for (String id : List.of("0", "-1", "abc", "9223372036854775808")) {
            for (String path : List.of("/orders/" + id + "/payments", "/admin/payments/" + id)) {
                mvc.perform(get(path).header("Authorization", "Bearer valid-token"))
                        .andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void userCanCreateOwnPaymentWithIdempotencyKey() throws Exception {
        login("USER");
        when(service.createOrContinue(10L, 20L, "payment-key"))
                .thenReturn(payment());

        mvc.perform(post("/orders/10/payments")
                        .header("Authorization", "Bearer valid-token")
                        .header("Idempotency-Key", "payment-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value("9007199254740993"));

        verify(service).createOrContinue(10L, 20L, "payment-key");
    }

    @Test
    void adminRetryPassesAuthenticatedOperatorAndIdempotencyKey() throws Exception {
        login("ADMIN");
        when(service.retryFailed(30L, 20L, "retry-key"))
                .thenReturn(payment());

        mvc.perform(post("/admin/payments/30/retry")
                        .header("Authorization", "Bearer valid-token")
                        .header("Idempotency-Key", "retry-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value("9007199254740993"));

        verify(service).retryFailed(30L, 20L, "retry-key");
    }

    @Test
    void paymentWritesRejectMissingHeadersAndInvalidIdsBeforeService() throws Exception {
        login("ADMIN");
        for (String path : List.of("/orders/0/payments", "/orders/-1/payments", "/orders/abc/payments",
                "/admin/payments/0/retry", "/admin/payments/-1/retry", "/admin/payments/abc/retry")) {
            mvc.perform(post(path)
                            .header("Authorization", "Bearer valid-token")
                            .header("Idempotency-Key", "payment-key"))
                    .andExpect(status().isBadRequest());
        }
        for (String path : List.of("/orders/10/payments", "/admin/payments/30/retry")) {
            mvc.perform(post(path)
                            .header("Authorization", "Bearer valid-token"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void paymentWritesRequireAuthenticationAndAdminRetryRequiresAdmin() throws Exception {
        mvc.perform(post("/orders/10/payments").header("Idempotency-Key", "payment-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/payments/30/retry").header("Idempotency-Key", "retry-key"))
                .andExpect(status().isUnauthorized());

        login("USER");
        mvc.perform(post("/admin/payments/30/retry")
                        .header("Authorization", "Bearer valid-token")
                        .header("Idempotency-Key", "retry-key"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void productionHasNoPaymentResultEntry() throws Exception {
        login("USER");
        mvc.perform(post("/orders/10/payments/result")
                        .header("Authorization", "Bearer valid-token")
                        .header("Idempotency-Key", "payment-key"))
                .andExpect(status().isNotFound());

        login("ADMIN");
        mvc.perform(post("/admin/payments/30")
                        .header("Authorization", "Bearer valid-token")
                        .header("Idempotency-Key", "retry-key"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }

    @Test
    void adminUsingUserRouteStillReceivesOwnershipNotFound() throws Exception {
        login("ADMIN");
        when(service.listByOrder(10L, 20L)).thenThrow(new ResourceNotFoundException());
        mvc.perform(get("/orders/10/payments").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        verify(service).listByOrder(10L, 20L);
    }
}
