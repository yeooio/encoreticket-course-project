package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 管理员电影详情视图。
 *
 * @author c1342
 */
public record AdminMovieDetailVO(
        String id,
        String title,
        String description,
        LocalDate releaseDate,
        Integer durationMinutes,
        OffsetDateTime publishAt,
        String status,
        Integer version,
        Instant createTime,
        Instant updateTime
) {
}
