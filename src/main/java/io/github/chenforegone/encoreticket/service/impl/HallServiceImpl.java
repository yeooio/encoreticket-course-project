package io.github.chenforegone.encoreticket.service.impl;

import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.mapper.HallMapper;
import io.github.chenforegone.encoreticket.mapper.SeatMapper;
import io.github.chenforegone.encoreticket.model.entity.Hall;
import io.github.chenforegone.encoreticket.model.vo.HallDetailVO;
import io.github.chenforegone.encoreticket.service.HallService;
import io.github.chenforegone.encoreticket.service.rules.ScreeningRules;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 影厅及固定物理布局维护，管理查询不触发过期清理。
 * @author c1342
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class HallServiceImpl implements HallService {
    private final HallMapper halls;
    private final SeatMapper seats;
    private final Clock clock;

    public HallServiceImpl(HallMapper halls, SeatMapper seats, Clock clock) {
        this.halls = halls;
        this.seats = seats;
        this.clock = clock;
    }

    @Override
    public HallDetailVO create(io.github.chenforegone.encoreticket.model.dto.CreateHallRequest request) {
        validatePositions(request.seats());
        Hall hall = new Hall();
        hall.setHallNo(text(request.hallNo(), 32));
        hall.setHallName(text(request.hallName(), 64));
        hall.setStatus(io.github.chenforegone.encoreticket.model.enums.HallStatus.ENABLED);
        hall.setScheduleVersion(0L);
        hall.setVersion(0);
        hall.setCreateTime(clock.instant());
        hall.setUpdateTime(hall.getCreateTime());
        halls.insert(hall);
        for (var position : request.seats()) {
            var seat = new io.github.chenforegone.encoreticket.model.entity.Seat();
            seat.setHallId(hall.getId());
            seat.setRowNumber(position.rowNumber());
            seat.setSeatNumber(position.seatNumber());
            seat.setStatus(io.github.chenforegone.encoreticket.model.enums.SeatStatus.ENABLED);
            seat.setVersion(0);
            seat.setCreateTime(hall.getCreateTime());
            seat.setUpdateTime(hall.getCreateTime());
            seats.insert(seat);
        }
        return get(hall.getId().toString());
    }

    @Override
    public HallDetailVO rename(String hallId, io.github.chenforegone.encoreticket.model.dto.UpdateHallRequest request) {
        Hall hall = halls.lockRow(ScreeningRules.parseId(hallId));
        if (hall == null) throw new ResourceNotFoundException();
        requireVersion(hall.getVersion(), request.version());
        String name = text(request.hallName(), 64);
        if (!name.equals(hall.getHallName())) {
            int expected = hall.getVersion();
            requireIncrement(expected);
            hall.setHallName(name);
            hall.setUpdateTime(clock.instant());
            if (halls.updateEdit(hall, expected) != 1) throw conflict();
            hall.setVersion(expected + 1);
        }
        return get(hallId);
    }

    @Override
    public HallDetailVO enable(String hallId, io.github.chenforegone.encoreticket.model.dto.HallVersionRequest request) {
        return hallStatus(hallId, request.version(), io.github.chenforegone.encoreticket.model.enums.HallStatus.ENABLED);
    }

    @Override
    public HallDetailVO disable(String hallId, io.github.chenforegone.encoreticket.model.dto.HallVersionRequest request) {
        return hallStatus(hallId, request.version(), io.github.chenforegone.encoreticket.model.enums.HallStatus.DISABLED);
    }

    private HallDetailVO hallStatus(String hallId, Integer version,
            io.github.chenforegone.encoreticket.model.enums.HallStatus target) {
        Hall hall = halls.lockRow(ScreeningRules.parseId(hallId));
        if (hall == null) throw new ResourceNotFoundException();
        requireVersion(hall.getVersion(), version);
        if (hall.getStatus() != target) {
            requireIncrement(version);
            hall.setStatus(target);
            hall.setUpdateTime(clock.instant());
            if (halls.updateEdit(hall, version) != 1) throw conflict();
            hall.setVersion(version + 1);
        }
        return get(hallId);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public io.github.chenforegone.encoreticket.model.vo.SeatLayoutVO layout(String hallId) {
        long id = ScreeningRules.parseId(hallId);
        Hall hall = halls.selectById(id);
        if (hall == null) throw new ResourceNotFoundException();
        requireEnabled(hall);
        var instant = clock.instant();
        var occupied = seats.activeOccupancy(id, java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.of("Asia/Shanghai")));
        return new io.github.chenforegone.encoreticket.model.vo.SeatLayoutVO(hallId, hall.getVersion(), instant,
                seats.allInHall(id).stream().map(seat -> seatView(seat, occupied)).toList());
    }

    @Override
    public io.github.chenforegone.encoreticket.model.vo.SeatVO disableSeat(String hallId, String seatId,
            io.github.chenforegone.encoreticket.model.dto.HallVersionRequest request) {
        return seatStatus(hallId, seatId, request.version(), io.github.chenforegone.encoreticket.model.enums.SeatStatus.DISABLED);
    }

    @Override
    public io.github.chenforegone.encoreticket.model.vo.SeatVO enableSeat(String hallId, String seatId,
            io.github.chenforegone.encoreticket.model.dto.HallVersionRequest request) {
        return seatStatus(hallId, seatId, request.version(), io.github.chenforegone.encoreticket.model.enums.SeatStatus.ENABLED);
    }

    private io.github.chenforegone.encoreticket.model.vo.SeatVO seatStatus(String hallId, String seatId, Integer version,
            io.github.chenforegone.encoreticket.model.enums.SeatStatus target) {
        long hid = ScreeningRules.parseId(hallId);
        long sid = ScreeningRules.parseId(seatId);
        Hall hall = halls.lockRow(hid);
        if (hall == null) throw new ResourceNotFoundException();
        var seat = seats.lockRow(sid);
        if (seat == null || !seat.getHallId().equals(hid)) throw new ResourceNotFoundException();
        requireVersion(seat.getVersion(), version);
        requireEnabled(hall);
        var occupied = seats.activeOccupancy(hid, ScreeningRules.now(clock));
        if (seat.getStatus() != target) {
            if (target == io.github.chenforegone.encoreticket.model.enums.SeatStatus.DISABLED
                    && occupied.stream().anyMatch(item -> item.seatId().equals(sid))) throw conflict();
            requireIncrement(version);
            seat.setStatus(target);
            seat.setUpdateTime(clock.instant());
            if (seats.updateEdit(seat, version) != 1) throw conflict();
            seat.setVersion(version + 1);
        }
        return seatView(seat, occupied);
    }

    @Override
    public io.github.chenforegone.encoreticket.model.vo.SeatsCreatedVO addSeats(String hallId,
            io.github.chenforegone.encoreticket.model.dto.CreateSeatsRequest request) {
        validatePositions(request.seats());
        long hid = ScreeningRules.parseId(hallId);
        Hall hall = halls.lockRow(hid);
        if (hall == null) throw new ResourceNotFoundException();
        requireEnabled(hall);
        if (halls.hasScreenings(hid)) throw conflict();
        var existing = seats.allInHall(hid).stream().map(seat ->
                new io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest(seat.getRowNumber(), seat.getSeatNumber()))
                .collect(java.util.stream.Collectors.toSet());
        if (request.seats().stream().anyMatch(existing::contains)) throw conflict();
        var added = new java.util.ArrayList<io.github.chenforegone.encoreticket.model.vo.SeatVO>();
        var now = clock.instant();
        for (var position : request.seats().stream().sorted(java.util.Comparator
                .comparing(io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest::rowNumber)
                .thenComparing(io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest::seatNumber)).toList()) {
            var seat = new io.github.chenforegone.encoreticket.model.entity.Seat();
            seat.setHallId(hid); seat.setRowNumber(position.rowNumber()); seat.setSeatNumber(position.seatNumber());
            seat.setStatus(io.github.chenforegone.encoreticket.model.enums.SeatStatus.ENABLED); seat.setVersion(0);
            seat.setCreateTime(now); seat.setUpdateTime(now);
            seats.insert(seat);
            added.add(seatView(seat, java.util.List.of()));
        }
        return new io.github.chenforegone.encoreticket.model.vo.SeatsCreatedVO(hallId, added);
    }

    private static void validatePositions(java.util.List<io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest> positions) {
        if (positions == null || positions.isEmpty() || positions.size() > 500)
            throw new io.github.chenforegone.encoreticket.common.exception.InvalidRequestException(
                    io.github.chenforegone.encoreticket.common.error.ErrorCode.INVALID_ARGUMENT);
        var unique = new java.util.HashSet<io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest>();
        for (var position : positions) {
            if (position == null || position.rowNumber() == null || position.rowNumber() < 1
                    || position.seatNumber() == null || position.seatNumber() < 1 || !unique.add(position))
                throw new io.github.chenforegone.encoreticket.common.exception.InvalidRequestException(
                        io.github.chenforegone.encoreticket.common.error.ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static void requireEnabled(Hall hall) {
        if (hall.getStatus() != io.github.chenforegone.encoreticket.model.enums.HallStatus.ENABLED)
            throw new io.github.chenforegone.encoreticket.common.exception.BusinessRuleException(
                    io.github.chenforegone.encoreticket.common.error.ErrorCode.BUSINESS_RULE_VIOLATION);
    }

    private static io.github.chenforegone.encoreticket.model.vo.SeatVO seatView(
            io.github.chenforegone.encoreticket.model.entity.Seat seat, java.util.List<SeatMapper.Occupancy> occupied) {
        boolean enabled = seat.getStatus() == io.github.chenforegone.encoreticket.model.enums.SeatStatus.ENABLED;
        var reasons = new java.util.ArrayList<String>();
        if (!enabled) reasons.add("ALREADY_DISABLED");
        occupied.stream().filter(item -> item.seatId().equals(seat.getId())).map(SeatMapper.Occupancy::status)
                .distinct().sorted().forEach(status -> reasons.add(status == 1 ? "SEAT_HAS_ACTIVE_HELD" : "SEAT_HAS_ACTIVE_SOLD"));
        return new io.github.chenforegone.encoreticket.model.vo.SeatVO(seat.getId().toString(), seat.getHallId().toString(),
                seat.getRowNumber(), seat.getSeatNumber(), seat.getStatus().name(), seat.getVersion(), !enabled,
                enabled && reasons.isEmpty(), reasons, seat.getCreateTime(), seat.getUpdateTime());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public io.github.chenforegone.encoreticket.common.response.PageResponse<HallDetailVO> list(
            io.github.chenforegone.encoreticket.model.dto.HallQueryRequest request) {
        if (request.page() < 1 || request.pageSize() < 1 || request.pageSize() > 100
                || !java.util.Set.of("createTime", "hallNo", "hallName").contains(request.sortBy())
                || !java.util.Set.of("asc", "desc").contains(request.sortOrder()))
            throw new io.github.chenforegone.encoreticket.common.exception.InvalidRequestException(
                    io.github.chenforegone.encoreticket.common.error.ErrorCode.INVALID_ARGUMENT);
        if (request.hallNo() != null) text(request.hallNo(), 32);
        if (request.hallName() != null) text(request.hallName(), 64);
        long total = halls.countFiltered(request);
        var values = halls.listFiltered(request, ((long) request.page() - 1) * request.pageSize(), request.pageSize());
        return new io.github.chenforegone.encoreticket.common.response.PageResponse<>(details(values), request.page(), request.pageSize(), total);
    }

    private java.util.List<HallDetailVO> details(java.util.List<Hall> values) {
        if (values.isEmpty()) return java.util.List.of();
        var ids = values.stream().map(Hall::getId).toList();
        var counts = halls.seatCounts(ids).stream().collect(java.util.stream.Collectors.toMap(HallMapper.SeatCounts::hallId, item -> item));
        var referenced = new java.util.HashSet<>(halls.hallsWithScreenings(ids));
        return values.stream().map(hall -> {
            boolean enabled = hall.getStatus() == io.github.chenforegone.encoreticket.model.enums.HallStatus.ENABLED;
            var reasons = new java.util.ArrayList<String>();
            if (!enabled) reasons.add("HALL_DISABLED");
            if (referenced.contains(hall.getId())) reasons.add("HALL_HAS_SCREENINGS");
            var count = counts.get(hall.getId());
            return new HallDetailVO(hall.getId().toString(), hall.getHallNo(), hall.getHallName(), hall.getStatus().name(), hall.getVersion(),
                    count == null ? 0 : count.total(), count == null ? 0 : count.enabled(), hall.getCreateTime(), hall.getUpdateTime(),
                    new io.github.chenforegone.encoreticket.model.vo.HallActionsVO(true, !enabled, enabled, enabled, reasons.isEmpty(), reasons));
        }).toList();
    }

    private static void requireVersion(Integer actual, Integer expected) {
        if (expected == null || expected < 0) throw new io.github.chenforegone.encoreticket.common.exception.InvalidRequestException(
                io.github.chenforegone.encoreticket.common.error.ErrorCode.INVALID_ARGUMENT);
        if (!expected.equals(actual)) throw conflict();
    }

    private static void requireIncrement(int version) {
        if (version == Integer.MAX_VALUE) throw conflict();
    }

    private static io.github.chenforegone.encoreticket.common.exception.ConflictException conflict() {
        return new io.github.chenforegone.encoreticket.common.exception.ConflictException(
                io.github.chenforegone.encoreticket.common.error.ErrorCode.RESOURCE_CONFLICT);
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.strip().codePointCount(0, value.strip().length()) > max
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new io.github.chenforegone.encoreticket.common.exception.InvalidRequestException(
                    io.github.chenforegone.encoreticket.common.error.ErrorCode.INVALID_ARGUMENT);
        }
        return value.strip();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public HallDetailVO get(String hallId) {
        Hall hall = halls.selectById(ScreeningRules.parseId(hallId));
        if (hall == null) {
            throw new ResourceNotFoundException();
        }
        return details(java.util.List.of(hall)).getFirst();
    }
}
