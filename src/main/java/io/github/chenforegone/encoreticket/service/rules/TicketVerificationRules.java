package io.github.chenforegone.encoreticket.service.rules;

import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.model.query.TicketVerificationProjection;
import java.util.Objects;

/**
 * 核销专属关联完整性规则，由业务服务在转换响应前调用。
 *
 * @author c1342
 */
public final class TicketVerificationRules {
    private TicketVerificationRules() { }

    public static void validate(TicketVerificationProjection p) {
        if (p == null || p.ticketId() == null || p.itemId() == null || p.orderId() == null
                || p.screeningId() == null || p.screeningSeatId() == null || p.seatId() == null
                || p.movieId() == null || p.hallId() == null || p.movieTitle() == null || p.hallName() == null
                || p.orderNo() == null || p.startTime() == null || p.endTime() == null
                || p.rowNumber() == null || p.rowNumber() < 1 || p.seatNumber() == null || p.seatNumber() < 1
                || !Objects.equals(p.orderScreeningId(), p.screeningId())
                || !Objects.equals(p.seatScreeningId(), p.screeningId())
                || !Objects.equals(p.seatOrderId(), p.orderId()) || !Objects.equals(p.saleStatus(), 2)
                || !Objects.equals(p.seatHallId(), p.hallId())) {
            throw new TicketVerificationException(ErrorCode.INTERNAL_ERROR);
        }
    }

    public static void validateSuccess(TicketVerificationProjection p) {
        validate(p);
        if (p.verificationId() == null || p.verifiedAt() == null || p.operatorId() == null
                || p.deviceCode() == null || !Objects.equals(p.ticketStatus(), 1)) {
            throw new TicketVerificationException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
