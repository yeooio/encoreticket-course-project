package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.mapper.HallMapper;
import io.github.chenforegone.encoreticket.mapper.SeatMapper;
import io.github.chenforegone.encoreticket.service.impl.HallServiceImpl;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 通过 HallService 公开边界验证维护规则，数据库适配器替身不证明真实事务。
 * @author c1342
 */
class HallServiceImplTest {
    private final HallMapper halls = mock(HallMapper.class);
    private final SeatMapper seats = mock(SeatMapper.class);
    private final HallService service = new HallServiceImpl(halls, seats, Clock.systemUTC());

    @Test
    void createMakesEnabledHallAndNonContinuousSeatsRetrievable() {
        var persisted = new java.util.LinkedHashMap<Long, io.github.chenforegone.encoreticket.model.entity.Seat>();
        org.mockito.Mockito.when(seats.insert(org.mockito.ArgumentMatchers.any(io.github.chenforegone.encoreticket.model.entity.Seat.class)))
                .thenAnswer(call -> {
                    var seat = call.getArgument(0, io.github.chenforegone.encoreticket.model.entity.Seat.class);
                    seat.setId(10L + persisted.size());
                    persisted.put(seat.getId(), seat);
                    return 1;
                });
        org.mockito.Mockito.when(seats.allInHall(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> persisted.values().stream()
                        .filter(seat -> seat.getHallId().equals(call.getArgument(0)))
                        .sorted(java.util.Comparator.comparing(io.github.chenforegone.encoreticket.model.entity.Seat::getRowNumber)
                                .thenComparing(io.github.chenforegone.encoreticket.model.entity.Seat::getSeatNumber)).toList());
        var request = new io.github.chenforegone.encoreticket.model.dto.CreateHallRequest(" H01 ", " 一号厅 ",
                java.util.List.of(new io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest(2, 7),
                        new io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest(1, 3)));
        org.mockito.Mockito.when(halls.insert(org.mockito.ArgumentMatchers.any(io.github.chenforegone.encoreticket.model.entity.Hall.class)))
                .thenAnswer(call -> {
                    var hall = call.getArgument(0, io.github.chenforegone.encoreticket.model.entity.Hall.class);
                    hall.setId(1L);
                    org.mockito.Mockito.when(halls.selectById(1L)).thenReturn(hall);
                    return 1;
                });
        var created = service.create(request);
        org.assertj.core.api.Assertions.assertThat(service.get(created.hallId()).hallNo()).isEqualTo("H01");
        org.assertj.core.api.Assertions.assertThat(created.status()).isEqualTo("ENABLED");
        var layout = service.layout(created.hallId());
        org.assertj.core.api.Assertions.assertThat(layout.hallId()).isEqualTo(created.hallId());
        org.assertj.core.api.Assertions.assertThat(layout.seats()).hasSize(2)
                .extracting(item -> java.util.List.of(item.rowNumber(), item.seatNumber()))
                .containsExactly(java.util.List.of(1, 3), java.util.List.of(2, 7));
        org.assertj.core.api.Assertions.assertThat(layout.seats()).allSatisfy(item -> {
            org.assertj.core.api.Assertions.assertThat(item.hallId()).isEqualTo(created.hallId());
            org.assertj.core.api.Assertions.assertThat(item.status()).isEqualTo("ENABLED");
            org.assertj.core.api.Assertions.assertThat(item.version()).isZero();
            org.assertj.core.api.Assertions.assertThat(item.seatId()).isNotBlank();
        });
    }

    @Test
    void createRejectsDuplicatePositionsBeforeCreatingHall() {
        var position = new io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest(1, 3);
        var request = new io.github.chenforegone.encoreticket.model.dto.CreateHallRequest("H01", "一号厅",
                java.util.List.of(position, position));
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.InvalidRequestException.class);
    }

    @Test
    void createdHallStartsWithIndependentEditVersionZero() {
        var request = new io.github.chenforegone.encoreticket.model.dto.CreateHallRequest("H02", "二号厅",
                java.util.List.of(new io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest(1, 1)));
        org.mockito.Mockito.when(halls.insert(org.mockito.ArgumentMatchers.any(io.github.chenforegone.encoreticket.model.entity.Hall.class)))
                .thenAnswer(call -> {
                    var hall = call.getArgument(0, io.github.chenforegone.encoreticket.model.entity.Hall.class);
                    hall.setId(2L);
                    org.mockito.Mockito.when(halls.selectById(2L)).thenReturn(hall);
                    return 1;
                });
        org.assertj.core.api.Assertions.assertThat(service.create(request).version()).isZero();
    }

    @Test
    void renameChangesNameAndVersionButRejectsStaleVersion() {
        var hall = new io.github.chenforegone.encoreticket.model.entity.Hall();
        hall.setId(1L); hall.setHallNo("H01"); hall.setHallName("原名"); hall.setVersion(0);
        hall.setStatus(io.github.chenforegone.encoreticket.model.enums.HallStatus.ENABLED);
        org.mockito.Mockito.when(halls.lockRow(1L)).thenReturn(hall);
        org.mockito.Mockito.when(halls.selectById(1L)).thenReturn(hall);
        org.mockito.Mockito.when(halls.updateEdit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(1);
        var renamed = service.rename("1", new io.github.chenforegone.encoreticket.model.dto.UpdateHallRequest("新名", 0));
        org.assertj.core.api.Assertions.assertThat(renamed.hallName()).isEqualTo("新名");
        org.assertj.core.api.Assertions.assertThat(renamed.version()).isEqualTo(1);
        assertThatThrownBy(() -> service.rename("1", new io.github.chenforegone.encoreticket.model.dto.UpdateHallRequest("覆盖", 0)))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ConflictException.class);
    }

    @Test
    void hallDisableAllowsExistingScreeningsAndSameVersionRetryDoesNotAdvanceVersion() {
        var hall = new io.github.chenforegone.encoreticket.model.entity.Hall();
        hall.setId(1L); hall.setHallNo("H01"); hall.setHallName("一号厅"); hall.setVersion(0);
        hall.setStatus(io.github.chenforegone.encoreticket.model.enums.HallStatus.ENABLED);
        org.mockito.Mockito.when(halls.lockRow(1L)).thenReturn(hall);
        org.mockito.Mockito.when(halls.selectById(1L)).thenReturn(hall);
        org.mockito.Mockito.when(halls.updateEdit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);
        var disabled = service.disable("1", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0));
        org.assertj.core.api.Assertions.assertThat(disabled.status()).isEqualTo("DISABLED");
        org.assertj.core.api.Assertions.assertThat(disabled.version()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(service.disable("1", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(1)).version()).isEqualTo(1);
        assertThatThrownBy(() -> service.disable("1", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0)))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ConflictException.class);
        org.assertj.core.api.Assertions.assertThat(service.enable("1", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(1)).status()).isEqualTo("ENABLED");
    }

    @Test
    void layoutIncludesDisabledSeatsAndActiveOccupancyReasonsWithoutCleaningHolds() {
        var hall = new io.github.chenforegone.encoreticket.model.entity.Hall();
        hall.setId(1L); hall.setVersion(0);
        hall.setStatus(io.github.chenforegone.encoreticket.model.enums.HallStatus.ENABLED);
        var seat = new io.github.chenforegone.encoreticket.model.entity.Seat();
        seat.setId(11L); seat.setHallId(1L); seat.setRowNumber(1); seat.setSeatNumber(3);
        seat.setVersion(0); seat.setStatus(io.github.chenforegone.encoreticket.model.enums.SeatStatus.ENABLED);
        org.mockito.Mockito.when(halls.selectById(1L)).thenReturn(hall);
        org.mockito.Mockito.when(seats.allInHall(1L)).thenReturn(java.util.List.of(seat));
        org.mockito.Mockito.when(seats.activeOccupancy(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(new io.github.chenforegone.encoreticket.mapper.SeatMapper.Occupancy(11L, 1)));
        var layout = service.layout("1");
        org.assertj.core.api.Assertions.assertThat(layout.seats().getFirst().canDisable()).isFalse();
        org.assertj.core.api.Assertions.assertThat(layout.seats().getFirst().disableBlockReasons()).containsExactly("SEAT_HAS_ACTIVE_HELD");
        seat.setStatus(io.github.chenforegone.encoreticket.model.enums.SeatStatus.DISABLED);
        org.assertj.core.api.Assertions.assertThat(service.layout("1").seats().getFirst().status()).isEqualTo("DISABLED");
        hall.setStatus(io.github.chenforegone.encoreticket.model.enums.HallStatus.DISABLED);
        assertThatThrownBy(() -> service.layout("1"))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.BusinessRuleException.class);
    }

    @Test
    void seatDisableRechecksOccupancyAndVersionThenAllowsAllSeatsDisabled() {
        var hall = new io.github.chenforegone.encoreticket.model.entity.Hall();
        hall.setId(1L); hall.setVersion(0); hall.setStatus(io.github.chenforegone.encoreticket.model.enums.HallStatus.ENABLED);
        var seat = new io.github.chenforegone.encoreticket.model.entity.Seat();
        seat.setId(11L); seat.setHallId(1L); seat.setRowNumber(1); seat.setSeatNumber(1);
        seat.setVersion(0); seat.setStatus(io.github.chenforegone.encoreticket.model.enums.SeatStatus.ENABLED);
        org.mockito.Mockito.when(halls.lockRow(1L)).thenReturn(hall);
        org.mockito.Mockito.when(seats.lockRow(11L)).thenReturn(seat);
        org.mockito.Mockito.when(seats.activeOccupancy(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(new SeatMapper.Occupancy(11L, 1)));
        assertThatThrownBy(() -> service.disableSeat("1", "11", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0)))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ConflictException.class);
        org.mockito.Mockito.when(seats.activeOccupancy(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(seats.updateEdit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(0))).thenReturn(1);
        var disabled = service.disableSeat("1", "11", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0));
        org.assertj.core.api.Assertions.assertThat(disabled.status()).isEqualTo("DISABLED");
        org.assertj.core.api.Assertions.assertThat(disabled.version()).isEqualTo(1);
        assertThatThrownBy(() -> service.disableSeat("1", "11", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0)))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ConflictException.class);
    }

    @Test
    void appendRejectsAnyScreeningAndOtherwiseReturnsOnlyNewSeats() {
        var hall = new io.github.chenforegone.encoreticket.model.entity.Hall();
        hall.setId(1L); hall.setVersion(0); hall.setStatus(io.github.chenforegone.encoreticket.model.enums.HallStatus.ENABLED);
        org.mockito.Mockito.when(halls.lockRow(1L)).thenReturn(hall);
        org.mockito.Mockito.when(halls.hasScreenings(1L)).thenReturn(true);
        var request = new io.github.chenforegone.encoreticket.model.dto.CreateSeatsRequest(java.util.List.of(
                new io.github.chenforegone.encoreticket.model.dto.SeatPositionRequest(2, 3)));
        assertThatThrownBy(() -> service.addSeats("1", request)).isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ConflictException.class);
        org.mockito.Mockito.when(halls.hasScreenings(1L)).thenReturn(false);
        org.mockito.Mockito.when(seats.insert(org.mockito.ArgumentMatchers.any(io.github.chenforegone.encoreticket.model.entity.Seat.class)))
                .thenAnswer(call -> { call.getArgument(0, io.github.chenforegone.encoreticket.model.entity.Seat.class).setId(21L); return 1; });
        var added = service.addSeats("1", request);
        org.assertj.core.api.Assertions.assertThat(added.seats()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(added.seats().getFirst().seatNumber()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(added.seats().getFirst().version()).isZero();
    }

    @Test
    void listUsesDefaultPaginationAndDetailExposesDisabledHallActions() {
        var hall = new io.github.chenforegone.encoreticket.model.entity.Hall();
        hall.setId(1L); hall.setHallNo("H01"); hall.setHallName("影厅"); hall.setVersion(2);
        hall.setStatus(io.github.chenforegone.encoreticket.model.enums.HallStatus.DISABLED);
        org.mockito.Mockito.when(halls.selectById(1L)).thenReturn(hall);
        var query = new io.github.chenforegone.encoreticket.model.dto.HallQueryRequest(null, null, null, null, null, null, null);
        org.mockito.Mockito.when(halls.countFiltered(query)).thenReturn(1L);
        org.mockito.Mockito.when(halls.listFiltered(query, 0L, 20)).thenReturn(java.util.List.of(hall));
        var listed = service.list(query);
        org.assertj.core.api.Assertions.assertThat(listed.total()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(listed.items().getFirst().actions().canViewSeats()).isFalse();
        org.assertj.core.api.Assertions.assertThat(service.get("1").actions().addSeatBlockReasons()).contains("HALL_DISABLED");
    }

    @Test
    void crossHallSeatCannotBeEnabledOrDisabled() {
        var hall = new io.github.chenforegone.encoreticket.model.entity.Hall();
        hall.setId(1L); hall.setStatus(io.github.chenforegone.encoreticket.model.enums.HallStatus.ENABLED);
        var foreign = new io.github.chenforegone.encoreticket.model.entity.Seat();
        foreign.setId(21L); foreign.setHallId(2L); foreign.setVersion(0);
        org.mockito.Mockito.when(halls.lockRow(1L)).thenReturn(hall);
        org.mockito.Mockito.when(seats.lockRow(21L)).thenReturn(foreign);
        var version = new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0);
        assertThatThrownBy(() -> service.enableSeat("1", "21", version)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.disableSeat("1", "21", version)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void disabledHallRejectsSeatEnableAndDisableEvenForSameTarget() {
        var hall = new io.github.chenforegone.encoreticket.model.entity.Hall();
        hall.setId(1L); hall.setStatus(io.github.chenforegone.encoreticket.model.enums.HallStatus.DISABLED);
        var seat = new io.github.chenforegone.encoreticket.model.entity.Seat();
        seat.setId(11L); seat.setHallId(1L); seat.setVersion(0);
        seat.setStatus(io.github.chenforegone.encoreticket.model.enums.SeatStatus.ENABLED);
        org.mockito.Mockito.when(halls.lockRow(1L)).thenReturn(hall);
        org.mockito.Mockito.when(seats.lockRow(11L)).thenReturn(seat);
        var version = new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(0);
        assertThatThrownBy(() -> service.enableSeat("1", "11", version))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.BusinessRuleException.class);
        assertThatThrownBy(() -> service.disableSeat("1", "11", version))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.BusinessRuleException.class);
    }

    @Test
    void seatSameTargetPreservesVersionAndTimestampButStaleVersionStillConflicts() {
        var hall = new io.github.chenforegone.encoreticket.model.entity.Hall();
        hall.setId(1L); hall.setStatus(io.github.chenforegone.encoreticket.model.enums.HallStatus.ENABLED);
        var seat = new io.github.chenforegone.encoreticket.model.entity.Seat();
        seat.setId(11L); seat.setHallId(1L); seat.setVersion(4); seat.setRowNumber(1); seat.setSeatNumber(3);
        seat.setStatus(io.github.chenforegone.encoreticket.model.enums.SeatStatus.ENABLED);
        var timestamp = java.time.Instant.parse("2026-10-01T00:00:00Z");
        seat.setUpdateTime(timestamp);
        org.mockito.Mockito.when(halls.lockRow(1L)).thenReturn(hall);
        org.mockito.Mockito.when(seats.lockRow(11L)).thenReturn(seat);
        var enabled = service.enableSeat("1", "11", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(4));
        org.assertj.core.api.Assertions.assertThat(enabled.version()).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(enabled.updatedAt()).isEqualTo(timestamp);
        seat.setStatus(io.github.chenforegone.encoreticket.model.enums.SeatStatus.DISABLED);
        var disabled = service.disableSeat("1", "11", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(4));
        org.assertj.core.api.Assertions.assertThat(disabled.version()).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(disabled.updatedAt()).isEqualTo(timestamp);
        assertThatThrownBy(() -> service.disableSeat("1", "11", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(3)))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ConflictException.class);
    }

    @Test
    void missingHallIsNotFound() {
        assertThatThrownBy(() -> service.get("1")).isInstanceOf(ResourceNotFoundException.class);
    }
}
