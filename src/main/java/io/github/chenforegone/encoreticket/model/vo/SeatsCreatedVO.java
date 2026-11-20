package io.github.chenforegone.encoreticket.model.vo;

import java.util.List;

/**
 * 仅包含本批成功新增的座位。
 * @author c1342
 */
public record SeatsCreatedVO(String hallId, List<SeatVO> seats) {
    public SeatsCreatedVO { seats = List.copyOf(seats); }
}
