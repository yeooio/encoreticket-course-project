package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.scheduler.PaymentIdempotencyCleanup;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 支付清理任务的作用域、UTC 截止与失败恢复测试。
 *
 * @author c1342
 */
class PaymentIdempotencyCleanupTest {
    @Test
    void usesUtcDeadlineAndOnlyBoundedPaymentOperations() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PaymentIdempotencyCleanup cleanup = new PaymentIdempotencyCleanup(jdbc,
                () -> Instant.parse("2026-10-19T12:00:00Z"));
        cleanup.cleanup();
        verify(jdbc).update(eq("DELETE FROM api_idempotency WHERE expires_at <= ? "
                        + "AND operation IN ('payment-create:v1','payment-admin-retry:v1') "
                        + "ORDER BY expires_at LIMIT 500"),
                eq(LocalDateTime.of(2026, 10, 19, 12, 0)));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void databaseFailureDoesNotPreventNextScheduledRun() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object.class)))
                .thenThrow(new IllegalStateException("故障注入")).thenReturn(0);
        PaymentIdempotencyCleanup cleanup = new PaymentIdempotencyCleanup(jdbc, Instant::now);
        assertDoesNotThrow(cleanup::cleanup);
        assertDoesNotThrow(cleanup::cleanup);
        verify(jdbc, times(2)).update(anyString(), any(Object.class));
    }
}
