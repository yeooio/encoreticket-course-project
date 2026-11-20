package io.github.chenforegone.encoreticket.model.dto;

import io.github.chenforegone.encoreticket.model.enums.HallStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

/**
 * 编号精确、名称字面包含及状态 AND 筛选。
 * @author c1342
 */
public record HallQueryRequest(String hallNo, String hallName, HallStatus status,
        @Min(1) Integer page, @Min(1) @Max(100) Integer pageSize, String sortBy, String sortOrder) {
    public HallQueryRequest {
        hallNo = CreateHallRequest.validatedText(hallNo, 32);
        hallName = CreateHallRequest.validatedText(hallName, 64);
        page = page == null ? 1 : page;
        pageSize = pageSize == null ? 20 : pageSize;
        sortBy = sortBy == null ? "createTime" : sortBy;
        sortOrder = sortOrder == null ? "desc" : sortOrder;
    }
    public String getNamePattern() {
        return hallName == null ? null : "%" + hallName.strip().replace("!", "!!").replace("%", "!%") .replace("_", "!_") + "%";
    }
}
