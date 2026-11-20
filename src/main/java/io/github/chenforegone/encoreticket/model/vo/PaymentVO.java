package io.github.chenforegone.encoreticket.model.vo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付记录响应视图。
 *
 * @author c1342
 */
public record PaymentVO(
        String paymentId,
        String paymentNo,
        String orderId,
        String amount,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    public static PaymentVO from(io.github.chenforegone.encoreticket.model.entity.Payment payment) {
        BigDecimal amount = payment.getAmount();
        return new PaymentVO(
                String.valueOf(payment.getId()),
                payment.getPaymentNo(),
                String.valueOf(payment.getOrderId()),
                amount == null ? null : amount.toPlainString(),
                payment.getCurrency(),
                payment.getStatus() == null ? null : payment.getStatus().getValue(),
                payment.getCreateTime(),
                payment.getUpdateTime()
        );
    }
}
