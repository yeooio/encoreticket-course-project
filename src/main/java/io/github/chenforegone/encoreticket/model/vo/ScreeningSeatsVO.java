package io.github.chenforegone.encoreticket.model.vo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 场次对外响应，与持久化实体隔离。
 *
 * @author c1342
 */
public record ScreeningSeatsVO(
        String screeningId, String status, boolean canPurchase,
        OffsetDateTime serverTime, List<ScreeningSeatVO> seats
) {
}

