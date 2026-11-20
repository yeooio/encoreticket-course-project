package io.github.chenforegone.encoreticket.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import io.github.chenforegone.encoreticket.model.enums.ScreeningStatus;

/**
 * 管理场次查询；时间范围左闭右开。
 *
 * @author c1342
 */
public record AdminScreeningQueryRequest(
        String movieId,
        String hallId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate screeningDate,
        @Min(value = 1, message = "页码必须大于等于 1") Integer page,
        @Min(value = 1, message = "页大小必须大于等于 1")
        @Max(value = 100, message = "页大小不能超过 100") Integer pageSize,
        ScreeningStatus status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTimeFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTimeTo,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime saleStartTimeFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime saleStartTimeTo,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime saleEndTimeFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime saleEndTimeTo,
        String sortBy,
        String sortOrder
) {
    public AdminScreeningQueryRequest {
        page = page == null ? 1 : page;
        pageSize = pageSize == null ? 20 : pageSize;
        sortBy = sortBy == null ? "startTime" : sortBy;
        sortOrder = sortOrder == null ? "asc" : sortOrder;
    }
}

