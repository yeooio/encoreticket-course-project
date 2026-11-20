package io.github.chenforegone.encoreticket.service.rules;

import io.github.chenforegone.encoreticket.model.enums.TicketDisplayStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 票券展示状态规则测试。
 *
 * @author c1342
 */
class TicketStatusResolverTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 19, 20, 0);

    @Test
    void paidTicketExpiresExactlyAtEndTime() {
        var resolver = new TicketStatusResolver();
        assertThat(resolver.resolve(0, 1, now.plusSeconds(1), now)).isEqualTo(TicketDisplayStatus.VALID);
        assertThat(resolver.resolve(0, 1, now, now)).isEqualTo(TicketDisplayStatus.EXPIRED);
        assertThat(resolver.resolve(0, 1, now.minusSeconds(1), now)).isEqualTo(TicketDisplayStatus.EXPIRED);
    }

    @ParameterizedTest
    @CsvSource({"1,1,USED", "1,4,USED", "2,5,VOID", "2,4,VOID", "0,4,REFUNDING"})
    void terminalAndRefundingStatusesTakePrecedence(int ticket, int order, TicketDisplayStatus expected) {
        var resolver = new TicketStatusResolver();
        for (int seconds : new int[]{-1, 0, 1}) {
            assertThat(resolver.resolve(ticket, order, now.plusSeconds(seconds), now)).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 3, 5, -1, 6})
    @NullSource
    void validTicketRejectsUnpaidRefundedAndUnknownOrders(Integer order) {
        assertThatThrownBy(() -> new TicketStatusResolver().resolve(0, order, now.plusHours(1), now))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 3, 99})
    @NullSource
    void rejectsUnknownTicketStatus(Integer ticket) {
        assertThatThrownBy(() -> new TicketStatusResolver().resolve(ticket, 1, now, now))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void terminalPrecedenceDoesNotHideMissingTimeOrUnknownOrder(int ticket) {
        var resolver = new TicketStatusResolver();
        assertThatThrownBy(() -> resolver.resolve(ticket, 1, null, now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolver.resolve(ticket, 1, now, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolver.resolve(ticket, null, now, now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolver.resolve(ticket, 99, now, now)).isInstanceOf(IllegalStateException.class);
    }
}
