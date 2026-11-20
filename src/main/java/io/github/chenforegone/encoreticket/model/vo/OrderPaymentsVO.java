package io.github.chenforegone.encoreticket.model.vo;

import io.github.chenforegone.encoreticket.model.enums.OrderStatus;

import java.util.List;

/**
 * 本人订单的支付记录及当前订单状态。
 *
 * @author c1342
 */
public record OrderPaymentsVO(String orderId, String orderStatus, List<PaymentVO> items) {
    public OrderPaymentsVO {
        items = List.copyOf(items);
    }

    public static String statusValue(OrderStatus status) {
        return status == OrderStatus.WAIT_PAY ? "PENDING_PAYMENT" : status.name();
    }
}
