package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;

/**
 * 影厅管理详情及列表条目，不包含物理布局。
 * @author c1342
 */
public record HallDetailVO(String hallId, String hallNo, String hallName, String status, int version,
        long seatCount, long enabledSeatCount, Instant createdAt, Instant updatedAt, HallActionsVO actions) {
}
