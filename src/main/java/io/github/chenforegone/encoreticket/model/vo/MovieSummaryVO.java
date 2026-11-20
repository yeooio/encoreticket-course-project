package io.github.chenforegone.encoreticket.model.vo;

import java.time.LocalDate;

/**
 * 电影列表视图。
 *
 * @author c1342
 */
public record MovieSummaryVO(
        String id,
        String title,
        String description,
        LocalDate releaseDate,
        Integer durationMinutes,
        String status
) {
}
