package io.github.chenforegone.encoreticket.model.dto;

import io.github.chenforegone.encoreticket.common.json.StrictIntegerDeserializer;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 创建电影请求。
 *
 * @author c1342
 */
public record CreateMovieRequest(
        @NotBlank(message = "电影名称不能为空")
        @Size(max = 255, message = "电影名称长度不能超过 255 个字符")
        String title,

        @Size(max = 10000, message = "电影简介长度不能超过 10000 个字符")
        String description,

        @NotNull(message = "上映日期不能为空")
        LocalDate releaseDate,

        @NotNull(message = "影片片长不能为空")
        @Positive(message = "影片片长必须为正整数")
        @JsonDeserialize(using = StrictIntegerDeserializer.class)
        Integer durationMinutes,

        @NotNull(message = "自动发行时间不能为空")
        @FutureOrPresent(message = "自动发行时间不能早于当前时间")
        OffsetDateTime publishAt
) {

    public CreateMovieRequest {
        title = title == null ? null : title.trim();
        description = normalizeOptional(description);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
