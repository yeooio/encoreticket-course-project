package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.service.impl.OrderExpirationService;
import io.github.chenforegone.encoreticket.service.transaction.OrderExpirationProcessor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 待支付订单的实际截止时间与有界释放契约。
 *
 * @author c1342
 */
class OrderExpirationServiceTest {

    @Test
    void expiresOnlyHeldSeatsAfterWinningOrderStateAndValidatingOwnership() {
        var mapper = org.mockito.Mockito.mock(io.github.chenforegone.encoreticket.mapper.OrderMapper.class);
        Instant now = Instant.parse("2026-10-01T11:45:00Z");
        var order = new io.github.chenforegone.encoreticket.model.entity.Order();
        order.setScreeningId(10L);
        var seat = new io.github.chenforegone.encoreticket.model.entity.ScreeningSeat();
        seat.setId(11L);
        seat.setOrderId(1L);
        seat.setScreeningId(10L);
        seat.setStatus(io.github.chenforegone.encoreticket.model.enums.ScreeningSeatStatus.HELD);
        org.mockito.Mockito.when(mapper.expireIfDue(1L, now)).thenReturn(1);
        org.mockito.Mockito.when(mapper.selectById(1L)).thenReturn(order);
        org.mockito.Mockito.when(mapper.selectOrderItemSeatIds(1L)).thenReturn(java.util.List.of(11L));
        org.mockito.Mockito.when(mapper.selectClaimedSeatsForUpdate(1L)).thenReturn(java.util.List.of(seat));
        org.mockito.Mockito.when(mapper.releaseHeldSeats(1L, 10L, now)).thenReturn(1);
        org.junit.jupiter.api.Assertions.assertTrue(new OrderExpirationProcessor(mapper).expire(1L, now));
        var writes = org.mockito.Mockito.inOrder(mapper);
        writes.verify(mapper).expireIfDue(1L, now);
        writes.verify(mapper).selectById(1L);
        writes.verify(mapper).selectOrderItemSeatIds(1L);
        writes.verify(mapper).selectClaimedSeatsForUpdate(1L);
        writes.verify(mapper).releaseHeldSeats(1L, 10L, now);
        seat.setStatus(io.github.chenforegone.encoreticket.model.enums.ScreeningSeatStatus.SOLD);
        org.mockito.Mockito.clearInvocations(mapper);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new OrderExpirationProcessor(mapper).expire(1L, now));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).releaseHeldSeats(1L, 10L, now);
    }

    @Test
    void lostOrderStateRaceNeverTouchesSeats() {
        var mapper = org.mockito.Mockito.mock(io.github.chenforegone.encoreticket.mapper.OrderMapper.class);
        Instant now = Instant.parse("2026-10-01T11:45:00Z");
        org.junit.jupiter.api.Assertions.assertFalse(new OrderExpirationProcessor(mapper).expire(1L, now));
        org.mockito.Mockito.verify(mapper).expireIfDue(1L, now);
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    }

    @Test
    void paymentDeadlineUsesTheEarlierSalesCutoffInCinemaTime() {
        assertEquals(Instant.parse("2026-10-01T11:45:00Z"),
                OrderExpirationService.calculateExpiresAt(Instant.parse("2026-10-01T11:40:00Z"),
                        LocalDateTime.parse("2026-10-01T19:45:00")));
        assertEquals(Instant.parse("2026-10-01T11:35:00Z"),
                OrderExpirationService.calculateExpiresAt(Instant.parse("2026-10-01T11:20:00Z"),
                        LocalDateTime.parse("2026-10-01T19:45:00")));
    }
}
