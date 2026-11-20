package io.github.chenforegone.encoreticket.model.vo;

import io.github.chenforegone.encoreticket.model.entity.Order;
import io.github.chenforegone.encoreticket.model.entity.Refund;

import java.time.Instant;
import java.util.List;

/**
 * 管理员支付详情。
 *
 * @author c1342
 */
public record PaymentDetailVO(
        PaymentVO payment,
        OrderSummary order,
        RefundSummary refund,
        List<PaymentAuditVO> audits,
        List<NotificationSummary> notifications
) {
    public PaymentDetailVO {
        audits = List.copyOf(audits);
        notifications = List.copyOf(notifications);
    }

    /**
     * 订单安全摘要，不包含票券凭证。
     * @author c1342
     */
    public record OrderSummary(String orderId, String orderNo, String screeningId,
                               String status, String totalAmount, String currency, Instant expiresAt) {
        public static OrderSummary from(Order order) {
            return new OrderSummary(String.valueOf(order.getId()), order.getOrderNo(),
                    String.valueOf(order.getScreeningId()), OrderPaymentsVO.statusValue(order.getStatus()),
                    order.getTotalAmount().toPlainString(), "CNY", order.getExpiresAt());
        }
    }

    /**
     * 当前支付关联的退款摘要；没有退款时返回 null。
     * @author c1342
     */
    public record RefundSummary(String refundId, String refundNo, String amount, String currency,
                                String status, Instant createdAt, Instant updatedAt) {
        public static RefundSummary from(Refund refund, String currency) {
            return new RefundSummary(String.valueOf(refund.getId()), refund.getRefundNo(),
                    refund.getAmount().toPlainString(), currency, refund.getStatus().name(),
                    refund.getCreateTime(), refund.getUpdateTime());
        }
    }

    /**
     * 已处理通知的元数据，不返回报文或报文摘要。
     * @author c1342
     */
    public record NotificationSummary(String provider, String eventId, String eventType,
                                      Instant processedAt, Instant createdAt) {
    }
}
