package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;
import java.util.List;

/**
 * 包含启用及停用座位的扁平物理布局。
 * @author c1342
 */
public record SeatLayoutVO(String hallId, int hallVersion, Instant serverTime, List<SeatVO> seats) {
    public SeatLayoutVO { seats = List.copyOf(seats); }
}
