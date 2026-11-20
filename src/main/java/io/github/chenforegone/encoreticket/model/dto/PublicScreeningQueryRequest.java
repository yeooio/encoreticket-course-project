package io.github.chenforegone.encoreticket.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;

/**
 * 公开场次筛选及分页，固定按放映时间和 ID 升序。
 *
 * @author c1342
 */
public record PublicScreeningQueryRequest(
        String movieId,
        String hallId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate screeningDate,
        @Min(value = 1, message = "页码必须大于等于 1") Integer page,
        @Min(value = 1, message = "页大小必须大于等于 1")
        @Max(value = 100, message = "页大小不能超过 100") Integer pageSize
) {
    public PublicScreeningQueryRequest {
        page = page == null ? 1 : page;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}


