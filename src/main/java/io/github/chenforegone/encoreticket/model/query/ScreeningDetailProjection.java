package io.github.chenforegone.encoreticket.model.query;

import io.github.chenforegone.encoreticket.model.enums.ScreeningStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 场次管理详情查询投影。
 *
 * @author c1342
 */
public record ScreeningDetailProjection(
        Long id,
        Long movieId,
        String movieTitle,
        Long hallId,
        String hallName,
        BigDecimal price,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime saleStartTime,
        LocalDateTime saleEndTime,
        LocalDateTime admissionOpenTime,
        ScreeningStatus status,
        Integer version
) {
}
