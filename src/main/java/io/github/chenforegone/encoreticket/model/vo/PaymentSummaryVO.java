package io.github.chenforegone.encoreticket.model.vo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 管理员支付摘要。
 *
 * @author c1342
 */
public record PaymentSummaryVO(
        String paymentId,
        String paymentNo,
        String orderId,
        String amount,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentSummaryVO from(PaymentVO payment) {
        return new PaymentSummaryVO(payment.paymentId(), payment.paymentNo(), payment.orderId(),
                payment.amount(), payment.currency(), payment.status(), payment.createdAt(), payment.updatedAt());
    }
}
