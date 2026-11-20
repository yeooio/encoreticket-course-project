package io.github.chenforegone.encoreticket.converter;

import io.github.chenforegone.encoreticket.model.query.TicketProjection;
import io.github.chenforegone.encoreticket.service.rules.TicketStatusResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 票券响应转换契约测试。
 *
 * @author c1342
 */
class TicketConverterTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 19, 18, 0);
    private final TicketConverter converter = new TicketConverter(new TicketStatusResolver());

    @Test
    void detailUsesLosslessIdsMoneyAndExplicitTimeZones() {
        var detail = converter.toDetail(projection(null), now);
        assertThat(detail.id()).isEqualTo("9007199254740993");
        assertThat(detail.ticketCode()).isEqualTo("SECRET-CODE");
        assertThat(detail.orderStatus()).isEqualTo("PAID");
        assertThat(detail.status()).isEqualTo("VALID");
        assertThat(detail.unitPrice()).isEqualTo("88.00");
        assertThat(detail.currency()).isEqualTo("CNY");
        assertThat(detail.startTime()).isEqualTo("2026-10-19T19:00:00+08:00");
        assertThat(detail.endTime()).isEqualTo("2026-10-19T21:00:00+08:00");
        assertThat(detail.createdAt()).isEqualTo("2026-10-18T10:00:00Z");
        assertThat(detail.verifiedAt()).isNull();
    }

    @Test
    void summaryAndStatusExposeOnlyTheirContractFields() {
        var projection = projection(Instant.parse("2026-10-19T09:30:00Z"));
        var summary = converter.toSummary(projection, now);
        assertThat(summary.id()).isEqualTo("9007199254740993");
        assertThat(summary.verifiedAt()).isEqualTo("2026-10-19T09:30:00Z");
        assertThat(summary.screeningStartTime()).isEqualTo("2026-10-19T19:00:00+08:00");
        var json = new tools.jackson.databind.json.JsonMapper();
        assertThat(json.writeValueAsString(summary)).doesNotContain("ticketCode", "SECRET-CODE");
        assertThat(json.writeValueAsString(converter.toStatus(projection, now))).isEqualTo("{\"status\":\"VALID\"}");
        assertThat(projection.toString()).doesNotContain("SECRET-CODE");
        assertThat(converter.toDetail(projection, now).toString()).doesNotContain("SECRET-CODE");
    }

    @Test
    void moneyNeverUsesExponentNotation() {
        var source = projection(null);
        var largePrice = new TicketProjection(source.id(), source.orderItemId(), source.orderId(), source.userId(),
                source.movieId(), source.screeningId(), source.hallId(), source.screeningSeatId(), source.seatId(),
                source.ticketCode(), source.orderNo(), source.movieTitle(), source.hallName(),
                source.ticketStatus(), source.orderStatus(), source.rowNumber(), source.seatNumber(),
                source.startTime(), source.endTime(), new BigDecimal("1E+3"), source.verifiedAt(), source.createdAt());
        assertThat(converter.toDetail(largePrice, now).unitPrice()).isEqualTo("1000");
    }

    @Test
    void missingRequiredAssociationOrFieldIsAnInternalDataError() throws Exception {
        var original = projection(null);
        var components = TicketProjection.class.getRecordComponents();
        var types = java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType)
                .toArray(Class<?>[]::new);
        for (int missing = 0; missing < components.length; missing++) {
            if (components[missing].getName().equals("verifiedAt")) {
                continue;
            }
            Object[] values = new Object[components.length];
            for (int index = 0; index < components.length; index++) {
                values[index] = index == missing ? null : components[index].getAccessor().invoke(original);
            }
            var broken = TicketProjection.class.getConstructor(types).newInstance(values);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.toDetail(broken, now))
                    .as(components[missing].getName()).isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.toSummary(broken, now))
                    .as(components[missing].getName()).isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.toStatus(broken, now))
                    .as(components[missing].getName()).isInstanceOf(IllegalStateException.class);
        }
    }

    private TicketProjection projection(Instant verifiedAt) {
        return new TicketProjection(9007199254740993L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L,
                "SECRET-CODE", "ORDER1", "电影", "一号厅", 0, 1, 2, 3,
                now.plusHours(1), now.plusHours(3), new BigDecimal("88.00"), verifiedAt,
                Instant.parse("2026-10-18T10:00:00Z"));
    }
}
