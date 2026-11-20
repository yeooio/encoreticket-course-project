package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;
import java.util.List;

/**
 * 后台物理座位及瞬时维护资格，不泄露订单信息。
 * @author c1342
 */
public record SeatVO(String seatId, String hallId, int rowNumber, int seatNumber, String status,
        int version, boolean canEnable, boolean canDisable, List<String> disableBlockReasons,
        Instant createdAt, Instant updatedAt) {
    public SeatVO { disableBlockReasons = List.copyOf(disableBlockReasons); }
}
