package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.repository.PaymentSettlementRepository;
import io.github.chenforegone.encoreticket.service.codec.IdempotencyJsonCodec;
import io.github.chenforegone.encoreticket.gateway.PaymentChannelEvent;
import io.github.chenforegone.encoreticket.gateway.PaymentGateway;
import io.github.chenforegone.encoreticket.common.exception.*;
import io.github.chenforegone.encoreticket.mapper.*;
import io.github.chenforegone.encoreticket.model.entity.*;
import io.github.chenforegone.encoreticket.model.enums.*;
import io.github.chenforegone.encoreticket.model.vo.PaymentVO;
import io.github.chenforegone.encoreticket.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 支付编排规则测试；真实数据库事务和行锁由独立集成测试验证。
 * @author c1342
 */
class PaymentServiceImplTest {
    private final Instant now = Instant.parse("2026-10-19T05:00:00Z");
    private PaymentMapper payments;
    private OrderMapper orders;
    private PaymentAuditMapper audits;
    private RefundMapper refunds;
    private PaymentIdempotencyMapper keys;
    private PaymentSettlementRepository settlement;
    private PaymentGateway gateway;
    private PaymentServiceImpl service;
    private Order order;
    private JsonMapper json;

    @BeforeEach
    void setup() {
        payments = mock(PaymentMapper.class); orders = mock(OrderMapper.class);
        audits = mock(PaymentAuditMapper.class); refunds = mock(RefundMapper.class);
        keys = mock(PaymentIdempotencyMapper.class); settlement = mock(PaymentSettlementRepository.class);
        gateway = mock(PaymentGateway.class); json = JsonMapper.builder().build();
        service = new PaymentServiceImpl(payments, orders, audits, refunds, keys, settlement, gateway, () -> now, json);
        order = new Order(); order.setId(10L); order.setUserId(20L); order.setVersion(0);
        order.setStatus(OrderStatus.WAIT_PAY); order.setTotalAmount(new BigDecimal("88.00"));
        when(orders.lockById(10L)).thenReturn(order);
        when(settlement.deadline(10L)).thenReturn(now.plusSeconds(300));
        when(payments.lockByOrder(10L)).thenReturn(List.of());
        when(payments.insert(any(Payment.class))).thenReturn(1);
        when(payments.transition(anyLong(), anyInt(), anyInt(), anyInt(), any())).thenReturn(1);
        when(audits.insert(any(PaymentAudit.class))).thenReturn(1);
        when(refunds.insert(any(Refund.class))).thenReturn(1);
        when(keys.save(anyLong(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        doAnswer(call -> {
            String digest = call.getArgument(3);
            when(keys.find(call.getArgument(0), call.getArgument(1), call.getArgument(2), true))
                    .thenReturn(new PaymentIdempotencyMapper.Snapshot(digest, null, now.plusSeconds(86400)));
            return null;
        }).when(keys).reserve(anyLong(), anyString(), anyString(), anyString(), any());
    }
    private Payment payment(PaymentStatus status) {
        Payment p = new Payment(); p.setId(30L); p.setOrderId(10L); p.setPaymentNo("P30");
        p.setAmount(new BigDecimal("88.00")); p.setCurrency("CNY"); p.setVersion(0); p.setStatus(status);
        when(payments.selectById(30L)).thenReturn(p);
        when(payments.selectOne(any())).thenReturn(p);
        when(payments.lockByOrder(10L)).thenReturn(List.of(p));
        when(settlement.attempt(30L)).thenReturn("attempt-1");
        return p;
    }
    private PaymentChannelEvent event(boolean success) {
        return new PaymentChannelEvent("SIM", "event-1", "P30", "attempt-1", success ? "SUCCESS" : "FAILED", new BigDecimal("88.00"), "CNY", success);
    }
    @Test void cancelledLateSuccessCreatesAutomaticCompensationWithoutTickets() {
        payment(PaymentStatus.PENDING); order.setStatus(OrderStatus.CANCELLED);
        assertThat(service.handleChannelEvent(event(true)).status()).isEqualTo("SUCCESS");
        var refund=org.mockito.ArgumentCaptor.forClass(Refund.class);
        verify(refunds).insert(refund.capture());
        assertThat(refund.getValue().getSource()).isEqualTo("LATE_PAYMENT");
        verify(settlement,never()).issueTickets(anyLong(),any());
    }
    @Test void createsPendingAndInvokesGatewayOnce() {
        PaymentVO result = service.createOrContinue(10L, 20L, "key");
        assertThat(result.status()).isEqualTo("PENDING"); assertThat(result.amount()).isEqualTo("88.00");
        verify(gateway).initiate(anyString(), anyString(), eq(new BigDecimal("88.00")), eq("CNY"));
        verify(keys).save(eq(20L), eq(PaymentServiceImpl.CREATE_OPERATION), eq("key"), anyString(), eq(now));
    }
    @Test void ownershipPrecedesReplay() {
        assertThatThrownBy(() -> service.createOrContinue(10L, 99L, "key")).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(keys, gateway);
    }
    @Test void pendingDoesNotInitiateAnotherAttempt() {
        payment(PaymentStatus.PENDING);
        assertThat(service.createOrContinue(10L, 20L, "key").paymentId()).isEqualTo("30");
        verifyNoInteractions(gateway);
    }
    @Test void failedUserRetryReusesPaymentWithFreshAttempt() {
        payment(PaymentStatus.FAILED);
        assertThat(service.createOrContinue(10L, 20L, "key").paymentId()).isEqualTo("30");
        verify(payments).transition(30L, 0, 2, 0, now); verify(payments, never()).insert(any(Payment.class));
        verify(settlement).attempt(eq(30L), anyString());
    }
    @Test void successfulPaymentOnRefundingOrderCannotStartNewOperation() {
        payment(PaymentStatus.SUCCESS); order.setStatus(OrderStatus.REFUNDING);
        assertThatThrownBy(() -> service.createOrContinue(10L, 20L, "key")).isInstanceOf(ConflictException.class);
        verifyNoInteractions(gateway);
    }
    @Test void successfulPaymentOnPaidOrderReturnsSuccessWithoutGateway() {
        payment(PaymentStatus.SUCCESS); order.setStatus(OrderStatus.PAID);
        assertThat(service.createOrContinue(10L, 20L, "key").status()).isEqualTo("SUCCESS");
        verifyNoInteractions(gateway);
    }
    @Test void exactDeadlineRejectsPayment() {
        when(settlement.deadline(10L)).thenReturn(now);
        assertThatThrownBy(() -> service.createOrContinue(10L, 20L, "key")).isInstanceOf(ConflictException.class);
        verifyNoInteractions(gateway);
    }
    @Test void adminRetryCreatesNewRecord() {
        payment(PaymentStatus.FAILED);
        assertThat(service.retryFailed(30L, 99L, "key").paymentId()).isNotEqualTo("30");
        verify(payments).insert(any(Payment.class));
    }
    @Test void adminCannotReactivateOlderRecordWhileAnotherIsCurrent() {
        Payment old = payment(PaymentStatus.FAILED); Payment latest = payment(PaymentStatus.PENDING); latest.setId(31L);
        when(payments.lockByOrder(10L)).thenReturn(List.of(latest, old));
        assertThatThrownBy(() -> service.retryFailed(30L, 99L, "key")).isInstanceOf(ConflictException.class);
        verifyNoInteractions(gateway);
    }
    @Test void successfulPaymentSellsSeatsAndIssuesTickets() {
        payment(PaymentStatus.PENDING);
        assertThat(service.handleChannelEvent(event(true)).status()).isEqualTo("SUCCESS");
        verify(settlement).settleSeats(10L, true, now); verify(settlement).issueTickets(10L, now);
        verify(settlement).orderState(10L, 0, 0, 1, now); verifyNoInteractions(refunds);
    }
    @Test void lateSuccessReleasesAndCreatesPendingRefundWithoutTickets() {
        payment(PaymentStatus.PENDING); when(settlement.deadline(10L)).thenReturn(now);
        service.handleChannelEvent(event(true));
        verify(settlement).settleSeats(10L, false, now);
        verify(settlement).orderState(10L, 0, 0, 3, now); verify(settlement).orderState(10L, 1, 3, 4, now);
        verify(refunds).insert(argThat((Refund r) -> r.getStatus() == RefundStatus.PENDING && r.getPaymentId().equals(30L)));
        verify(settlement, never()).issueTickets(anyLong(), any());
    }
    @Test void successNeverRegressesOnFailure() {
        payment(PaymentStatus.SUCCESS);
        assertThat(service.handleChannelEvent(event(false)).status()).isEqualTo("SUCCESS");
        verify(payments, never()).transition(anyLong(), anyInt(), anyInt(), anyInt(), any());
        verify(settlement).notification(any(), any(), eq(now));
    }
    @Test void staleFailureDoesNotOverwriteCurrentAttempt() {
        payment(PaymentStatus.PENDING); when(settlement.attempt(30L)).thenReturn("attempt-2");
        when(settlement.knownAttempt(30L, "attempt-1")).thenReturn(true);
        assertThat(service.handleChannelEvent(event(false)).status()).isEqualTo("PENDING");
        verify(payments, never()).transition(anyLong(), anyInt(), anyInt(), anyInt(), any());
    }
    @Test void identicalDuplicateAcknowledgesWithoutSettlement() throws Exception {
        Payment p = payment(PaymentStatus.SUCCESS);
        PaymentChannelEvent e = event(true);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(List.of(e.provider(), e.eventId(),
                e.paymentNo(), e.attemptId(), e.eventType(), "88", e.currency(), e.successful())).getBytes(StandardCharsets.UTF_8));
        when(settlement.notificationHash("SIM", "event-1")).thenReturn(hash);
        assertThat(service.handleChannelEvent(e)).isEqualTo(PaymentVO.from(p));
        verify(settlement, never()).settleSeats(anyLong(), anyBoolean(), any());
        verifyNoInteractions(audits, refunds);
    }
    @Test void oldKnownSuccessfulAttemptIsNotSilentlyDropped() {
        payment(PaymentStatus.PENDING);
        when(settlement.attempt(30L)).thenReturn("attempt-2");
        when(settlement.knownAttempt(30L, "attempt-1")).thenReturn(true);
        assertThat(service.handleChannelEvent(event(true)).status()).isEqualTo("SUCCESS");
        verify(settlement).issueTickets(10L, now);
    }

    @Test void olderPaymentLateSuccessIsRefundedAfterAdminRetry() {
        Payment old = payment(PaymentStatus.FAILED);
        Payment latest = new Payment();
        latest.setId(31L);
        latest.setStatus(PaymentStatus.PENDING);
        when(payments.lockByOrder(10L)).thenReturn(List.of(latest, old));
        when(settlement.deadline(10L)).thenReturn(now);
        assertThat(service.handleChannelEvent(event(true)).status()).isEqualTo("SUCCESS");
        verify(refunds).insert(argThat((Refund r) -> r.getPaymentId().equals(30L)));
        verify(settlement, never()).issueTickets(anyLong(), any());
    }
    @Test void unknownAttemptIsRejectedEvenIfFailure() {
        payment(PaymentStatus.PENDING);
        when(settlement.attempt(30L)).thenReturn("attempt-2");
        assertThatThrownBy(() -> service.handleChannelEvent(event(false))).isInstanceOf(ConflictException.class);
        verifyNoInteractions(audits, refunds);
    }
    @Test void mismatchingDuplicateEventConflicts() {
        payment(PaymentStatus.PENDING); when(settlement.notificationHash("SIM", "event-1")).thenReturn(new byte[32]);
        assertThatThrownBy(() -> service.handleChannelEvent(event(true))).isInstanceOf(ConflictException.class);
        verify(settlement, never()).issueTickets(anyLong(), any());
    }
    @Test void amountMismatchFailsBeforeWrites() {
        Payment p = payment(PaymentStatus.PENDING); p.setAmount(BigDecimal.ONE);
        assertThatThrownBy(() -> service.handleChannelEvent(event(true))).isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(refunds, audits);
    }
    @Test void optimisticConflictStopsSettlement() {
        payment(PaymentStatus.PENDING); when(payments.transition(anyLong(), anyInt(), anyInt(), anyInt(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.handleChannelEvent(event(false))).isInstanceOf(ConflictException.class);
        verify(settlement, never()).notification(any(), any(), any());
    }
    @Test void originalResponseReplaysEvenWhenCurrentStateChanged() throws Exception {
        Payment p = payment(PaymentStatus.PENDING); PaymentVO original = PaymentVO.from(p);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((PaymentServiceImpl.CREATE_OPERATION + ":10").getBytes(StandardCharsets.UTF_8)));
        when(keys.find(20L, PaymentServiceImpl.CREATE_OPERATION, "key", true))
                .thenReturn(new PaymentIdempotencyMapper.Snapshot(digest, json.writeValueAsString(original), now.plusSeconds(1)));
        p.setStatus(PaymentStatus.SUCCESS);
        assertThat(service.createOrContinue(10L, 20L, "key").status()).isEqualTo("PENDING");
        verify(payments, never()).lockByOrder(anyLong());
    }
    @Test void sameKeyDifferentTargetConflicts() {
        when(keys.find(20L, PaymentServiceImpl.CREATE_OPERATION, "key", true))
                .thenReturn(new PaymentIdempotencyMapper.Snapshot("other", "{}", now.plusSeconds(1)));
        assertThatThrownBy(() -> service.createOrContinue(10L, 20L, "key")).isInstanceOf(ConflictException.class);
    }
    @Test void expiredKeyIsReplaced() {
        when(keys.find(20L, PaymentServiceImpl.CREATE_OPERATION, "key", true))
                .thenReturn(new PaymentIdempotencyMapper.Snapshot("old", "{}", now));
        assertThat(service.createOrContinue(10L, 20L, "key").status()).isEqualTo("PENDING");
        verify(keys).delete(20L, PaymentServiceImpl.CREATE_OPERATION, "key");
    }
    @Test void jacksonCodecPreservesEscapesNullAndInstant() {
        var codec = new IdempotencyJsonCodec(json);
        PaymentVO value = new PaymentVO("1", "P\"\\", "2", "88.00", "CNY", "PENDING", now, null);
        assertThat(codec.read(codec.write(value))).isEqualTo(value);
    }
}
