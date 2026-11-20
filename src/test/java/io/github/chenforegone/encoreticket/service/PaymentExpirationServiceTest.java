package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.service.transaction.PaymentExpirationService;

import io.github.chenforegone.encoreticket.repository.PaymentSettlementRepository;
import io.github.chenforegone.encoreticket.mapper.OrderMapper;
import io.github.chenforegone.encoreticket.model.entity.Order;
import io.github.chenforegone.encoreticket.model.enums.OrderStatus;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 过期处理与支付截止及订单锁规则保持一致。
 * @author c1342
 */
class PaymentExpirationServiceTest {
    @Test void expiresAtExactDeadlineWithSeatReleaseBeforeVersionedOrderUpdate() {
        var orders = mock(OrderMapper.class);
        var repository = mock(PaymentSettlementRepository.class);
        Instant now = Instant.parse("2026-10-19T05:00:00Z");
        Order order = new Order(); order.setId(10L); order.setVersion(7); order.setStatus(OrderStatus.WAIT_PAY);
        when(orders.lockById(10L)).thenReturn(order); when(repository.deadline(10L)).thenReturn(now);
        assertThat(new PaymentExpirationService(orders, repository, () -> now).expire(10L)).isTrue();
        var sequence = inOrder(orders, repository);
        sequence.verify(orders).lockById(10L);
        sequence.verify(repository).deadline(10L);
        sequence.verify(repository).settleSeats(10L, false, now);
        sequence.verify(repository).orderState(10L, 7, 0, 3, now);
    }
    @Test void paidOrderNeverExpires() {
        var orders = mock(OrderMapper.class); var repository = mock(PaymentSettlementRepository.class);
        Order order = new Order(); order.setStatus(OrderStatus.PAID);
        when(orders.lockById(10L)).thenReturn(order);
        assertThat(new PaymentExpirationService(orders, repository, Instant::now).expire(10L)).isFalse();
        verifyNoInteractions(repository);
    }
}
