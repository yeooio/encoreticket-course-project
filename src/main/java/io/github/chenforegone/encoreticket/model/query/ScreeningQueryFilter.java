package io.github.chenforegone.encoreticket.model.query;

import java.time.LocalDateTime;

/**
 * 服务层完成格式验证和时区转换后的 SQL 查询条件。
 *
 * @author c1342
 */
public record ScreeningQueryFilter(
        Long movieId, Long hallId, Integer status,
        LocalDateTime dateFrom, LocalDateTime dateTo,
        LocalDateTime startTimeFrom, LocalDateTime startTimeTo,
        LocalDateTime saleStartTimeFrom, LocalDateTime saleStartTimeTo,
        LocalDateTime saleEndTimeFrom, LocalDateTime saleEndTimeTo,
        String sortBy, String sortOrder
) {
}

