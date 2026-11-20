package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.ConflictException;
import io.github.chenforegone.encoreticket.gateway.PaymentChannelEvent;
import io.github.chenforegone.encoreticket.mapper.PaymentSettlementMapper;
import io.github.chenforegone.encoreticket.mapper.PaymentSettlementMapper.NotificationHashRow;
import io.github.chenforegone.encoreticket.repository.PaymentSettlementRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 支付结算仓储的通知投影与座位归属契约测试。
 *
 * @author c1342
 */
class PaymentSettlementRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z");
    private final PaymentSettlementMapper mapper = mock(PaymentSettlementMapper.class);
    private final PaymentSettlementRepository repository = new PaymentSettlementRepository(mapper);

    @Test
    void sellsSeatsWithoutClearingTheirOrderAndLocksAllSeatsBeforeWriting() {
        when(mapper.selectSeatIds(10L)).thenReturn(List.of(101L, 102L));
        when(mapper.selectItemSeatIds(10L)).thenReturn(List.of(101L, 102L));
        for (long seat : List.of(101L, 102L)) {
            when(mapper.lockSeat(seat)).thenReturn(seat);
            when(mapper.updateSeatState(seat, 10L, 2, 10L, NOW)).thenReturn(1);
        }

        repository.settleSeats(10L, true, NOW);

        var sequence = inOrder(mapper);
        sequence.verify(mapper).selectSeatIds(10L);
        sequence.verify(mapper).lockSeat(101L);
        sequence.verify(mapper).lockSeat(102L);
        sequence.verify(mapper).selectItemSeatIds(10L);
        sequence.verify(mapper).updateSeatState(101L, 10L, 2, 10L, NOW);
        sequence.verify(mapper).updateSeatState(102L, 10L, 2, 10L, NOW);
    }

    @Test
    void releasesHeldSeatsAndClearsTheirOrder() {
        when(mapper.selectSeatIds(10L)).thenReturn(List.of(101L));
        when(mapper.lockSeat(101L)).thenReturn(101L);
        when(mapper.updateSeatState(101L, 10L, 0, null, NOW)).thenReturn(1);

        repository.settleSeats(10L, false, NOW);

        verify(mapper).updateSeatState(101L, 10L, 0, null, NOW);
        verify(mapper, never()).selectItemSeatIds(anyLong());
    }

    @Test
    void mismatchedOrderItemsPreventSellingAnySeat() {
        when(mapper.selectSeatIds(10L)).thenReturn(List.of(101L));
        when(mapper.lockSeat(101L)).thenReturn(101L);
        when(mapper.selectItemSeatIds(10L)).thenReturn(List.of(102L));

        assertThatThrownBy(() -> repository.settleSeats(10L, true, NOW))
                .isInstanceOf(ConflictException.class);
        verify(mapper, never()).updateSeatState(anyLong(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void failedConditionalSeatUpdateRemainsAConflict() {
        when(mapper.selectSeatIds(10L)).thenReturn(List.of(101L));
        when(mapper.lockSeat(101L)).thenReturn(101L);

        assertThatThrownBy(() -> repository.settleSeats(10L, false, NOW))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_STATE_CONFLICT);
    }

    @Test
    void ordinaryLookupUnwrapsTheSingleRowAndPreservesMissingAsNull() {
        byte[] hash = new byte[32];
        hash[0] = (byte) 0xff;
        when(mapper.selectNotificationHash("SIM", "event")).thenReturn(new NotificationHashRow(hash));

        assertThat(repository.notificationHash("SIM", "event")).containsExactly(hash);
        assertThat(repository.notificationHash("SIM", "missing")).isNull();
    }

    @Test
    void matchingNotificationUsesLockedProjectionAfterInsertAndAcceptsReplay() {
        byte[] hash = new byte[32];
        when(mapper.selectNotificationHashForUpdate("SIM", "event"))
                .thenReturn(new NotificationHashRow(hash.clone()));

        assertThatCode(() -> repository.notification(event(), hash, NOW)).doesNotThrowAnyException();

        var sequence = inOrder(mapper);
        sequence.verify(mapper).insertNotificationIfAbsent(anyLong(), eq("SIM"), eq("event"),
                eq("P30"), eq("SUCCESS"), eq(hash), eq(NOW), eq(NOW), eq("attempt"));
        sequence.verify(mapper).selectNotificationHashForUpdate("SIM", "event");
        verify(mapper, never()).selectNotificationHash(any(), any());
    }

    @Test
    void changedNotificationHashIsAnIdempotencyConflict() {
        byte[] existing = new byte[32];
        existing[31] = 1;
        when(mapper.selectNotificationHashForUpdate("SIM", "event"))
                .thenReturn(new NotificationHashRow(existing));

        assertThatThrownBy(() -> repository.notification(event(), new byte[32], NOW))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
    }

    @Test
    void missingNotificationAfterInsertIsAnIdempotencyConflict() {
        assertThatThrownBy(() -> repository.notification(event(), new byte[32], NOW))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
    }

    private PaymentChannelEvent event() {
        return new PaymentChannelEvent("SIM", "event", "P30", "attempt", "SUCCESS",
                new BigDecimal("88.00"), "CNY", true);
    }
}
