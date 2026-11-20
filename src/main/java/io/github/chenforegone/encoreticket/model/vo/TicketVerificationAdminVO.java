package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 管理员只读核销审计结果。
 *
 * @author c1342
 */
public record TicketVerificationAdminVO(String verificationId, String ticketId, String screeningId,
        String movieTitle, OffsetDateTime startTime, OffsetDateTime endTime, String hallName,
        int rowNumber, int seatNumber, String status, Instant verifiedAt,
        String orderNo, String operatorId, String deviceCode) { }
