package io.github.chenforegone.encoreticket.model.vo;

import java.time.LocalDate;

/**
 * 公开电影详情视图。
 *
 * @author c1342
 */
public record MovieDetailVO(
        String id,
        String title,
        String description,
        LocalDate releaseDate,
        Integer durationMinutes,
        String status
) {
}
