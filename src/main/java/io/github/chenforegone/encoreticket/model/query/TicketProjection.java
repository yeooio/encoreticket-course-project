package io.github.chenforegone.encoreticket.model.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 票券关联查询投影，关联主键用于检测缺失数据。
 *
 * @author c1342
 */
public record TicketProjection(
        Long id, Long orderItemId, Long orderId, Long userId,
        Long movieId, Long screeningId, Long hallId, Long screeningSeatId, Long seatId,
        String ticketCode, String orderNo, String movieTitle, String hallName,
        Integer ticketStatus, Integer orderStatus, Integer rowNumber, Integer seatNumber,
        LocalDateTime startTime, LocalDateTime endTime, BigDecimal unitPrice,
        Instant verifiedAt, Instant createdAt
) {
    /** 防止调试日志泄露完整票券码。 */
    @Override
    public String toString() {
        return "TicketProjection[id=" + id + "]";
    }
}
