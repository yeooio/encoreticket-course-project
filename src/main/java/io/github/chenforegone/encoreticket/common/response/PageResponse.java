package io.github.chenforegone.encoreticket.common.response;

import java.util.List;

/**
 * 从第一页开始的分页响应。
 *
 * @author c1342
 */
public record PageResponse<T>(List<T> items, int page, int pageSize, long total) {
    public PageResponse {
        items = List.copyOf(items);
    }
}
