package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.repository.PaymentSettlementRepository;
import io.github.chenforegone.encoreticket.gateway.PaymentGateway;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.mapper.*;
import io.github.chenforegone.encoreticket.model.entity.*;
import io.github.chenforegone.encoreticket.model.enums.*;
import io.github.chenforegone.encoreticket.model.vo.PaymentDetailVO;
import io.github.chenforegone.encoreticket.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 支付只读查询与响应摘要测试，不执行支付事务写路径。
 *
 * @author c1342
 */
class PaymentQueryServiceTest {
    private final Instant now = Instant.parse("2026-10-19T05:00:00Z");
    private final PaymentMapper payments = mock(PaymentMapper.class);
    private final OrderMapper orders = mock(OrderMapper.class);
    private final PaymentAuditMapper audits = mock(PaymentAuditMapper.class);
    private final RefundMapper refunds = mock(RefundMapper.class);
    private final PaymentIdempotencyMapper keys = mock(PaymentIdempotencyMapper.class);
    private final PaymentSettlementRepository settlement = mock(PaymentSettlementRepository.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PaymentServiceImpl service = new PaymentServiceImpl(payments, orders, audits, refunds,
            keys, settlement, gateway, () -> now, JsonMapper.builder().build());
    private Order order;
    private Payment payment;

    @BeforeEach
    void setup() {
        order = new Order();
        order.setId(10L); order.setOrderNo("O10"); order.setUserId(20L); order.setScreeningId(40L);
        order.setStatus(OrderStatus.REFUNDING); order.setTotalAmount(new BigDecimal("88.00"));
        order.setExpiresAt(now);
        payment = new Payment();
        payment.setId(30L); payment.setOrderId(10L); payment.setPaymentNo("P30");
        payment.setAmount(new BigDecimal("88.00")); payment.setCurrency("CNY");
        payment.setStatus(PaymentStatus.SUCCESS); payment.setCreateTime(now); payment.setUpdateTime(now);
        when(orders.selectById(10L)).thenReturn(order);
        when(payments.selectById(30L)).thenReturn(payment);
        when(payments.selectNotificationSummaries("P30")).thenReturn(List.of());
    }

    @Test
    void orderResponseIncludesStatusEvenWithoutPayments() {
        order.setStatus(OrderStatus.WAIT_PAY);
        when(payments.selectList(any())).thenReturn(List.of());
        var result = service.listByOrder(10L, 20L);
        assertThat(result.orderId()).isEqualTo("10");
        assertThat(result.orderStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(result.items()).isEmpty();
        verifyNoInteractions(keys, settlement, gateway, refunds, audits);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void orderResponseMapsRecordsAndUsesStableScopedQuery() {
        when(payments.selectList(any())).thenReturn(List.of(payment));
        var result = service.listByOrder(10L, 20L);
        assertThat(result.items()).singleElement().satisfies(p -> {
            assertThat(p.paymentId()).isEqualTo("30");
            assertThat(p.amount()).isEqualTo("88.00");
            assertThat(p.status()).isEqualTo("SUCCESS");
        });
        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(payments).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("order_id =", "ORDER BY create_time DESC,id DESC");
        assertThat(query.getValue().getParamNameValuePairs()).containsValue(10L);
    }

    @Test
    void nonexistentAndForeignOrdersDoNotQueryPayments() {
        assertThatThrownBy(() -> service.listByOrder(10L, 99L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.listByOrder(11L, 20L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.listByOrder(0L, 20L)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(payments);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void detailIncludesOrderPaymentRefundAuditsAndNotificationMetadata() {
        Refund refund = new Refund();
        refund.setId(50L); refund.setPaymentId(30L); refund.setRefundNo("R50");
        refund.setAmount(new BigDecimal("88.00")); refund.setStatus(RefundStatus.PENDING);
        refund.setCreateTime(now); refund.setUpdateTime(now);
        when(refunds.selectOne(any())).thenReturn(refund);
        PaymentAudit audit = new PaymentAudit();
        audit.setOperation("CHANNEL_SUCCESS"); audit.setFromStatus(0); audit.setToStatus(1);
        audit.setSource("CHANNEL"); audit.setCreateTime(now);
        when(audits.selectList(any())).thenReturn(List.of(audit));
        when(payments.selectNotificationSummaries("P30")).thenReturn(List.of(
                new PaymentDetailVO.NotificationSummary("SIM", "event-1", "SUCCESS", now, now)));
        var detail = service.adminDetail(30L);
        assertThat(detail.order().orderNo()).isEqualTo("O10");
        assertThat(detail.order().status()).isEqualTo("REFUNDING");
        assertThat(detail.order().totalAmount()).isEqualTo("88.00");
        assertThat(detail.refund().refundId()).isEqualTo("50");
        assertThat(detail.refund().status()).isEqualTo("PENDING");
        assertThat(detail.refund().currency()).isEqualTo("CNY");
        assertThat(detail.audits()).singleElement().satisfies(a -> {
            assertThat(a.fromStatus()).isEqualTo("PENDING");
            assertThat(a.toStatus()).isEqualTo("SUCCESS");
        });
        assertThat(detail.notifications()).singleElement().satisfies(n -> assertThat(n.eventId()).isEqualTo("event-1"));
        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(refunds).selectOne(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("payment_id =");
        assertThat(query.getValue().getParamNameValuePairs()).containsValue(30L);
        verifyNoInteractions(keys, settlement, gateway);
    }

    @Test
    void detailWithoutRefundOrHistoryHasExplicitEmptyValues() {
        order.setStatus(OrderStatus.WAIT_PAY);
        var detail = service.adminDetail(30L);
        assertThat(detail.order().status()).isEqualTo("PENDING_PAYMENT");
        assertThat(detail.refund()).isNull();
        assertThat(detail.notifications()).isEmpty();
        assertThat(detail.audits()).isEmpty();
    }

    @Test
    void missingPaymentAndBrokenOrderAreRejected() {
        assertThatThrownBy(() -> service.adminDetail(31L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.adminDetail(-1L)).isInstanceOf(InvalidRequestException.class);
        when(orders.selectById(10L)).thenReturn(null);
        assertThatThrownBy(() -> service.adminDetail(30L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(refunds);
    }
}
