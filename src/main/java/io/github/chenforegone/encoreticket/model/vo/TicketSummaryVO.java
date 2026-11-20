package io.github.chenforegone.encoreticket.model.vo;

/**
 * 不含票券码的票券摘要。
 *
 * @author c1342
 */
public record TicketSummaryVO(
        String id, String orderId, String movieId, String screeningId,
        String orderNo, String movieTitle, String hallName,
        String screeningStartTime, String screeningEndTime,
        Integer rowNumber, Integer seatNumber, String status, String verifiedAt
) {
}
