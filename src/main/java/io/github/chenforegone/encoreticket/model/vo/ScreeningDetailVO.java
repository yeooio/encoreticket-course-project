package io.github.chenforegone.encoreticket.model.vo;

import java.time.OffsetDateTime;

/**
 * 场次对外响应，与持久化实体隔离。
 *
 * @author c1342
 */
public record ScreeningDetailVO(
        String id, String movieId, String hallId,
        String movieTitle, String hallName, String price, String currency,
        OffsetDateTime startTime, OffsetDateTime endTime,
        OffsetDateTime saleStartTime, OffsetDateTime saleEndTime, String status, boolean canPurchase,
        Integer durationMinutesSnapshot, Integer turnaroundMinutesSnapshot
) {
}

