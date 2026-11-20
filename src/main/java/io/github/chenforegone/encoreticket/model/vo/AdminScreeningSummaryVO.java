package io.github.chenforegone.encoreticket.model.vo;

import java.time.OffsetDateTime;

/**
 * 管理端场次信息，与持久化实体隔离。
 *
 * @author c1342
 */
public record AdminScreeningSummaryVO(
        String id, String movieId, String hallId,
        String movieTitle, String hallName, String price, String currency,
        OffsetDateTime startTime, OffsetDateTime endTime,
        OffsetDateTime saleStartTime, OffsetDateTime saleEndTime, String status, Integer version
) {
}

