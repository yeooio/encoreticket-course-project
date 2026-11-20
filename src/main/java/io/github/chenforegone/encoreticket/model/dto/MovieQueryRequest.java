package io.github.chenforegone.encoreticket.model.dto;

import io.github.chenforegone.encoreticket.model.enums.MovieStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 电影列表查询参数。
 *
 * @author c1342
 */
public record MovieQueryRequest(
        @Min(value = 1, message = "页码必须大于等于 1")
        Integer page,

        @Min(value = 1, message = "页大小必须大于等于 1")
        @Max(value = 100, message = "页大小不能超过 100")
        Integer pageSize,

        @Size(max = 255, message = "电影名称搜索条件长度不能超过 255 个字符")
        String title,
        MovieStatus status,
        LocalDate releaseDateFrom,
        LocalDate releaseDateTo,
        OffsetDateTime publishAtFrom,
        OffsetDateTime publishAtTo,
        String sortBy,
        String sortOrder
) {

    public MovieQueryRequest {
        page = page == null ? 1 : page;
        pageSize = pageSize == null ? 20 : pageSize;
        title = title == null ? null : title.trim();
        sortBy = sortBy == null || sortBy.isBlank() ? "releaseDate" : sortBy;
        sortOrder = sortOrder == null || sortOrder.isBlank() ? "asc" : sortOrder.toLowerCase();
    }
}
