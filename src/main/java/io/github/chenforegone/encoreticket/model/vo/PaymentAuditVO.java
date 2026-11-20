package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;

/**
 * 支付审计时间线响应。
 *
 * @author c1342
 */
public record PaymentAuditVO(
        String operation,
        String fromStatus,
        String toStatus,
        String source,
        String requestId,
        String eventId,
        String remark,
        Instant createdAt
) {
}
