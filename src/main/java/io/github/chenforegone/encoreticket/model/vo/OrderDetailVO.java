package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 本人订单详情及创建成功快照。
 * @author c1342
 */
public record OrderDetailVO(String orderId, String orderNo, String screeningId, String movieId,
        String movieTitle, String hallId, String hallName, OffsetDateTime startTime,
        OffsetDateTime endTime, String status, int seatCount, String totalAmount, String currency,
        Instant expiresAt, Instant createdAt, Instant updatedAt, List<Item> items) {
    public OrderDetailVO {
        if(startTime!=null) startTime=startTime.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        if(endTime!=null) endTime=endTime.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        items=List.copyOf(items);
    }
    /**
     * 订单座位明细，不包含票券凭证。
     * @author c1342
     */
    public record Item(String orderItemId, String screeningSeatId, String seatId,
            int rowNumber, int seatNumber, String unitPrice) { }
}
