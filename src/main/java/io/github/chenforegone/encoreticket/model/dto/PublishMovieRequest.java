package io.github.chenforegone.encoreticket.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 手动发布电影请求。
 *
 * @author c1342
 */
public record PublishMovieRequest(
        @NotNull(message = "版本号不能为空")
        Integer version
) {
}
