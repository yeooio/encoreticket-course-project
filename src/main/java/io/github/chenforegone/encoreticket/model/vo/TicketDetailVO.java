package io.github.chenforegone.encoreticket.model.vo;

/**
 * 本人票券完整详情。
 *
 * @author c1342
 */
public record TicketDetailVO(
        String id, String ticketCode, String status,
        String orderId, String orderNo, String orderStatus,
        String movieId, String movieTitle, String screeningId,
        String hallId, String hallName, String startTime, String endTime,
        Integer rowNumber, Integer seatNumber, String unitPrice, String currency,
        String verifiedAt, String createdAt
) {
    /** 防止调试日志泄露完整票券码。 */
    @Override
    public String toString() {
        return "TicketDetailVO[id=" + id + ", status=" + status + "]";
    }
}
