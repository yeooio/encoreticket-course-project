package io.github.chenforegone.encoreticket.model.dto;

import io.github.chenforegone.encoreticket.model.enums.PaymentStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;

/**
 * 管理员支付分页查询条件。
 *
 * @author c1342
 */
public record AdminPaymentQueryRequest(
        @Min(1) Integer page,
        @Min(1) @Max(100) Integer pageSize,
        PaymentStatus paymentStatus,
        @jakarta.validation.constraints.Positive Long orderId,
        @jakarta.validation.constraints.Size(max = 64) String paymentNo,
        Instant createdFrom,
        Instant createdTo
) {
    public int actualPage() { return page == null ? 1 : page; }
    public int actualPageSize() { return pageSize == null ? 20 : pageSize; }
}
