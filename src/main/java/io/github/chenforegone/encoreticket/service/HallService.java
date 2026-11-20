package io.github.chenforegone.encoreticket.service;

/**
 * 影厅和物理座位业务服务。
 * @author c1342
 */
public interface HallService {
    io.github.chenforegone.encoreticket.model.vo.HallDetailVO create(
            io.github.chenforegone.encoreticket.model.dto.CreateHallRequest request);
    io.github.chenforegone.encoreticket.common.response.PageResponse<io.github.chenforegone.encoreticket.model.vo.HallDetailVO> list(
            io.github.chenforegone.encoreticket.model.dto.HallQueryRequest request);
    io.github.chenforegone.encoreticket.model.vo.HallDetailVO get(String hallId);
    io.github.chenforegone.encoreticket.model.vo.SeatLayoutVO layout(String hallId);
    io.github.chenforegone.encoreticket.model.vo.SeatsCreatedVO addSeats(String hallId,
            io.github.chenforegone.encoreticket.model.dto.CreateSeatsRequest request);
    io.github.chenforegone.encoreticket.model.vo.SeatVO disableSeat(String hallId, String seatId,
            io.github.chenforegone.encoreticket.model.dto.HallVersionRequest request);
    io.github.chenforegone.encoreticket.model.vo.SeatVO enableSeat(String hallId, String seatId,
            io.github.chenforegone.encoreticket.model.dto.HallVersionRequest request);
    io.github.chenforegone.encoreticket.model.vo.HallDetailVO enable(String hallId,
            io.github.chenforegone.encoreticket.model.dto.HallVersionRequest request);
    io.github.chenforegone.encoreticket.model.vo.HallDetailVO disable(String hallId,
            io.github.chenforegone.encoreticket.model.dto.HallVersionRequest request);
    io.github.chenforegone.encoreticket.model.vo.HallDetailVO rename(String hallId,
            io.github.chenforegone.encoreticket.model.dto.UpdateHallRequest request);
}
