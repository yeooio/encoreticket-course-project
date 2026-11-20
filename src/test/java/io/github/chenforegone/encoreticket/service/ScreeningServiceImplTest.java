package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.service.impl.OrderExpirationService;
import io.github.chenforegone.encoreticket.service.rules.HallScheduleGuard;

import io.github.chenforegone.encoreticket.common.exception.BusinessRuleException;
import io.github.chenforegone.encoreticket.common.exception.ConflictException;
import io.github.chenforegone.encoreticket.converter.ScreeningConverter;
import io.github.chenforegone.encoreticket.mapper.*;
import io.github.chenforegone.encoreticket.model.dto.*;
import io.github.chenforegone.encoreticket.model.entity.*;
import io.github.chenforegone.encoreticket.model.enums.*;
import io.github.chenforegone.encoreticket.model.query.ScreeningProjection;
import io.github.chenforegone.encoreticket.service.impl.ScreeningServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 合并后的完整场次功能与锁后入场规则回归。
 * @author c1342
 */
class ScreeningServiceImplTest {
    private final ScreeningMapper mapper = mock(ScreeningMapper.class);
    private final MovieMapper movies = mock(MovieMapper.class);
    private final HallScheduleGuard guard = mock(HallScheduleGuard.class);
    private final SeatMapper seats = mock(SeatMapper.class);
    private final ScreeningSeatMapper screeningSeats = mock(ScreeningSeatMapper.class);
    private final ScreeningQueryMapper queries = mock(ScreeningQueryMapper.class);
    private final OrderExpirationService expiration = mock(OrderExpirationService.class);
    private final Clock clock = mock(Clock.class);
    private final ScreeningServiceImpl service = new ScreeningServiceImpl(mapper, movies, guard, seats,
            screeningSeats, queries, new ScreeningConverter(), expiration, clock);
    private final OffsetDateTime start = OffsetDateTime.parse("2026-10-20T12:00:00Z");
    private final LocalDateTime localStart = LocalDateTime.of(2026, 10, 20, 20, 0);

    @BeforeEach
    void prepare() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-18T00:00:00Z"));
        Movie movie = new Movie();
        movie.setId(2L);
        movie.setVersion(0);
        movie.setDurationMinutes(120);
        movie.setReleaseDate(LocalDate.of(2026, 10, 1));
        movie.setStatus(MovieStatus.ONLINE);
        when(movies.selectById(2L)).thenReturn(movie);
        when(movies.claimForScheduling(2L, 0)).thenReturn(1);
        when(mapper.countEnabledHall(3L)).thenReturn(1);
        when(mapper.countEnabledHall(4L)).thenReturn(1);
        Seat seat = new Seat();
        seat.setId(10L);
        when(seats.enabledInHall(anyLong())).thenReturn(List.of(seat));
        when(mapper.replaceWaitSale(any(), eq(0), any())).thenReturn(1);
        ScreeningProjection detail = new ScreeningProjection();
        detail.setStatus(ScreeningStatus.WAIT_SALE);
        when(queries.findAdmin(1L)).thenReturn(detail);
    }

    @Test
    void initializesSeatsAndDurationAndAdmissionSnapshots() {
        when(guard.lockHalls(3L)).thenReturn(List.of());
        when(mapper.insert(any(Screening.class))).thenAnswer(call -> {
            Screening value = call.getArgument(0);
            value.setId(1L);
            assertEquals(localStart, value.getStartTime());
            assertEquals(localStart.plusHours(2), value.getEndTime());
            assertEquals(120, value.getDurationMinutesSnapshot());
            assertEquals(15, value.getTurnaroundMinutesSnapshot());
            assertEquals(localStart.minusMinutes(15), value.getAdmissionOpenTime());
            assertEquals(ScreeningStatus.WAIT_SALE, value.getStatus());
            return 1;
        });
        assertEquals("1", service.create(create(null)).id());
        verify(screeningSeats).insertSeats(argThat(batch -> batch.size() == 1
                && batch.getFirst().getSeatId().equals(10L)));
    }

    @Test
    void preservesAdmissionFromLockedRowAndRefreshesDuration() {
        Screening locked = current();
        locked.setAdmissionOpenTime(localStart.minusMinutes(30));
        lock(locked);
        service.update("1", update("3"));
        verify(mapper).replaceWaitSale(argThat(value -> value.getAdmissionOpenTime().equals(locked.getAdmissionOpenTime())
                && value.getDurationMinutesSnapshot() == 120), eq(0), any());
        verify(screeningSeats, never()).deleteForScreening(anyLong());
    }

    @Test
    void rejectsWhenLockWaitCrossesSaleStart() {
        lock(current());
        when(guard.lockHalls(3L, 3L)).thenAnswer(call -> {
            when(clock.instant()).thenReturn(current().getSaleStartTime().toInstant(ZoneOffset.ofHours(8)));
            return List.of(current());
        });
        assertThrows(ConflictException.class, () -> service.update("1", update("3")));
        verify(mapper, never()).replaceWaitSale(any(), anyInt(), any());
    }

    @Test
    void rejectsLockedStatusAndStaleVersion() {
        Screening locked = current();
        locked.setStatus(ScreeningStatus.ON_SALE);
        lock(locked);
        assertThrows(ConflictException.class, () -> service.update("1", update("3")));
        locked.setStatus(ScreeningStatus.WAIT_SALE);
        locked.setVersion(1);
        assertThrows(ConflictException.class, () -> service.update("1", update("3")));
    }

    @Test
    void rejectsMovedScreeningAndDoesNotUseSnapshot() {
        when(mapper.selectById(1L)).thenReturn(current());
        when(guard.lockHalls(3L, 3L)).thenReturn(List.of());
        assertThrows(ConflictException.class, () -> service.update("1", update("3")));
    }

    @Test
    void rebuildsSeatsOnlyForUnreferencedHallChange() {
        when(mapper.selectById(1L)).thenReturn(current());
        when(guard.lockHalls(3L, 4L)).thenReturn(List.of(current()));
        service.update("1", update("4"));
        var order = inOrder(mapper, screeningSeats);
        order.verify(mapper).replaceWaitSale(any(), eq(0), any());
        order.verify(mapper).countOrders(1L);
        order.verify(mapper).countOccupiedSeats(1L);
        order.verify(screeningSeats).deleteForScreening(1L);
        order.verify(screeningSeats).insertSeats(anyList());
    }

    @Test
    void protectsAnyOrderHistoryDuringEdit() {
        lock(current());
        when(mapper.countOrders(1L)).thenReturn(1L);
        assertThrows(ConflictException.class, () -> service.update("1", update("3")));
        verify(screeningSeats, never()).deleteForScreening(anyLong());
    }

    @Test
    void rejectsAdmissionOverlapAndKeepsClosedSlotReusable() {
        Screening previous = current();
        previous.setId(10L);
        previous.setStartTime(localStart.minusHours(3));
        previous.setEndTime(localStart.minusMinutes(20));
        when(guard.lockHalls(3L)).thenReturn(List.of(previous));
        assertThrows(BusinessRuleException.class, () -> service.create(create(start.minusMinutes(30))));
        previous.setStatus(ScreeningStatus.CLOSED);
        when(mapper.insert(any(Screening.class))).thenAnswer(call -> {
            ((Screening) call.getArgument(0)).setId(1L);
            return 1;
        });
        assertEquals("1", service.create(create(start.minusMinutes(30))).id());
    }

    @Test
    void offSaleDoesNotReleaseOrdersAndLifecycleDoesNotReopenIt() {
        Screening value = current();
        value.setStatus(ScreeningStatus.ON_SALE);
        when(mapper.selectById(1L)).thenReturn(value);
        when(mapper.changeStatus(anyLong(), anyInt(), anyInt(), anyInt(), any(), any())).thenReturn(1);
        service.offSale("1", new ScreeningVersionRequest(0));
        verifyNoInteractions(expiration, screeningSeats);
        value.setStatus(ScreeningStatus.OFF_SALE);
        clearInvocations(mapper);
        service.advanceLifecycle(1L);
        verify(mapper, never()).changeStatus(anyLong(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void closeRejectsActiveOrdersAfterClaimingScreening() {
        when(mapper.selectById(1L)).thenReturn(current());
        when(mapper.claimForClose(eq(1L), eq(0), any())).thenReturn(1);
        when(mapper.countActiveOrders(1L)).thenReturn(1L);
        assertThrows(ConflictException.class, () -> service.close("1", new ScreeningVersionRequest(0)));
        verify(mapper, never()).finishClose(anyLong(), anyInt(), any());
    }

    @Test
    void locksAllHallsBeforeSchedulesInAscendingOrder() {
        ScreeningMapper locks = mock(ScreeningMapper.class);
        when(locks.lockHall(anyLong())).thenAnswer(call -> call.getArgument(0));
        when(locks.lockHallSchedule(anyLong())).thenReturn(List.of());
        new HallScheduleGuard(locks).lockHalls(9L, 3L, 9L);
        var order = inOrder(locks);
        order.verify(locks).lockHall(3L);
        order.verify(locks).lockHall(9L);
        order.verify(locks).lockHallSchedule(3L);
        order.verify(locks).lockHallSchedule(9L);
    }

    @Test
    void mapperXmlResolvesAllRestoredStatementsAndSnapshots() throws Exception {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : List.of("mapper/ScreeningMapper.xml", "mapper/ScreeningQueryMapper.xml",
                "mapper/ScreeningSeatMapper.xml", "mapper/ScreeningLifecycleMapper.xml", "mapper/OrderMapper.xml",
                "mapper/HallMapper.xml", "mapper/SeatMapper.xml")) {
            try (var stream = org.apache.ibatis.io.Resources.getResourceAsStream(resource)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(stream, configuration, resource,
                        configuration.getSqlFragments()).parse();
            }
        }
        assertTrue(configuration.hasStatement(ScreeningMapper.class.getName() + ".replaceWaitSale"));
        assertTrue(configuration.hasStatement(OrderMapper.class.getName() + ".lockById"));
        assertTrue(configuration.hasStatement(OrderMapper.class.getName() + ".expireIfDue"));
        var map = configuration.getResultMap(ScreeningMapper.class.getName() + ".ScreeningResultMap");
        assertTrue(map.getResultMappings().stream().anyMatch(value -> "admissionOpenTime".equals(value.getProperty())));
        assertTrue(map.getResultMappings().stream().anyMatch(value -> "durationMinutesSnapshot".equals(value.getProperty())));
    }

    private void lock(Screening locked) {
        when(mapper.selectById(1L)).thenReturn(current());
        when(guard.lockHalls(3L, 3L)).thenReturn(List.of(locked));
    }

    private Screening current() {
        Screening value = new Screening();
        value.setId(1L);
        value.setMovieId(2L);
        value.setHallId(3L);
        value.setVersion(0);
        value.setStatus(ScreeningStatus.WAIT_SALE);
        value.setStartTime(localStart);
        value.setEndTime(localStart.plusHours(2));
        value.setSaleStartTime(localStart.minusDays(1));
        value.setSaleEndTime(localStart.minusMinutes(15));
        value.setAdmissionOpenTime(localStart.minusMinutes(15));
        value.setTurnaroundMinutesSnapshot(15);
        return value;
    }

    private CreateScreeningRequest create(OffsetDateTime admission) {
        return new CreateScreeningRequest("2", "3", "10.00", start, start.minusDays(1), admission, null);
    }

    private UpdateScreeningRequest update(String hall) {
        return new UpdateScreeningRequest("2", hall, "10.00", start, start.minusDays(1), null, 0);
    }
}
