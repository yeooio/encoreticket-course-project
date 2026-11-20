package io.github.chenforegone.encoreticket.model.query;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 核销专属关联投影，不依赖票券查询实体。
 *
 * @author c1342
 */
public record TicketVerificationProjection(Long ticketId, String ticketCode, Integer ticketStatus,
        Long itemId, Long orderId, Long orderScreeningId, Integer orderStatus, String orderNo,
        Long screeningSeatId, Long seatScreeningId, Long seatOrderId, Integer saleStatus,
        Long seatId, Long seatHallId, Integer rowNumber, Integer seatNumber,
        Long screeningId, Long movieId, Long hallId, String movieTitle, String hallName,
        Long verificationId, Long operatorId, String deviceCode, Instant verifiedAt,
        LocalDateTime startTime, LocalDateTime endTime) { }
