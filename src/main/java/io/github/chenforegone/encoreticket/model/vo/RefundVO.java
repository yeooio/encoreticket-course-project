package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;

/**
 * 不含渠道和账户信息的退款响应。
 * @author c1342
 */
public record RefundVO(String refundId, String refundNo, String orderId, String paymentId,
        String amount, String currency, String status, String source, Instant createdAt, Instant updatedAt) { }
