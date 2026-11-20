package io.github.chenforegone.encoreticket.service.transaction;

import io.github.chenforegone.encoreticket.common.time.Clock;

import io.github.chenforegone.encoreticket.repository.PaymentSettlementRepository;
import io.github.chenforegone.encoreticket.mapper.OrderMapper;
import io.github.chenforegone.encoreticket.model.entity.Order;
import io.github.chenforegone.encoreticket.model.enums.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单订单过期处理，与支付共享订单锁、座位锁顺序及实际支付截止。
 * @author c1342
 */
@Service
public class PaymentExpirationService {
    private final OrderMapper orders;
    private final PaymentSettlementRepository settlement;
    private final Clock clock;
    public PaymentExpirationService(OrderMapper orders, PaymentSettlementRepository settlement, Clock clock) {
        this.orders = orders;
        this.settlement = settlement;
        this.clock = clock;
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean expire(long orderId) {
        Order order = orders.lockById(orderId);
        if (order == null || order.getStatus() != OrderStatus.WAIT_PAY) return false;
        var now = clock.instant();
        if (now.isBefore(settlement.deadline(orderId))) return false;
        settlement.settleSeats(orderId, false, now);
        settlement.orderState(orderId, order.getVersion(), OrderStatus.WAIT_PAY.getCode(), OrderStatus.EXPIRED.getCode(), now);
        return true;
    }
}
