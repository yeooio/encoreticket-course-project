package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 检票成功结果，不含首次操作员、设备或购票人信息。
 *
 * @author c1342
 */
public record TicketVerificationVO(String verificationId, String ticketId, String screeningId,
        String movieTitle, OffsetDateTime startTime, OffsetDateTime endTime, String hallName,
        int rowNumber, int seatNumber, String status, Instant verifiedAt) { }
