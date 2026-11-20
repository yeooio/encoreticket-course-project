package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 本人订单列表摘要，不返回明细及票码。
 * @author c1342
 */
public record OrderSummaryVO(String orderId,String orderNo,String screeningId,String movieId,String movieTitle,
        String hallId,String hallName,OffsetDateTime startTime,OffsetDateTime endTime,String status,
        int seatCount,String totalAmount,String currency,Instant expiresAt,Instant createdAt,Instant updatedAt) {
    public static OrderSummaryVO from(OrderDetailVO o) {
        return new OrderSummaryVO(o.orderId(),o.orderNo(),o.screeningId(),o.movieId(),o.movieTitle(),o.hallId(),o.hallName(),o.startTime(),o.endTime(),o.status(),o.seatCount(),o.totalAmount(),o.currency(),o.expiresAt(),o.createdAt(),o.updatedAt());
    }
}
