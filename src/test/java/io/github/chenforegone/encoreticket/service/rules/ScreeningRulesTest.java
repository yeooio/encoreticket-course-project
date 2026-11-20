package io.github.chenforegone.encoreticket.service.rules;

import io.github.chenforegone.encoreticket.common.exception.ApplicationException;
import io.github.chenforegone.encoreticket.model.entity.Movie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Business boundaries for cinema-local scheduling, fixed sales cutoff, and positive ticket prices.
 * Expected times are worked examples rather than calculations copied from the implementation.
 *
 * @author c1342
 */
class ScreeningRulesTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-01T08:00:00");

    @Test
    void releaseDayIsInclusiveAndEquivalentOffsetsRepresentTheSameScreening() {
        ScreeningRules.Times china = ScreeningRules.plan(movie(120), time("2026-10-01T12:00:00+08:00"),
                time("2026-10-01T09:00:00+08:00"), false, NOW);
        ScreeningRules.Times utc = ScreeningRules.plan(movie(120), time("2026-10-01T04:00:00Z"),
                time("2026-10-01T01:00:00Z"), false, NOW);

        assertEquals(china, utc);
        assertEquals(LocalDateTime.parse("2026-10-01T12:00:00"), utc.start());
        assertEquals(LocalDateTime.parse("2026-10-01T14:00:00"), utc.end());
        assertEquals(LocalDateTime.parse("2026-10-01T11:45:00"), utc.saleEnd());
    }

    @Test
    void movieReleaseDateIsComparedInCinemaTimeRatherThanTheRequestsOffset() {
        ScreeningRules.Times planned = ScreeningRules.plan(movie(120), time("2026-09-30T23:00:00-07:00"),
                time("2026-10-01T01:00:00Z"), false, NOW);
        assertEquals(LocalDateTime.parse("2026-10-01T14:00:00"), planned.start());

        Movie notReleased = movie(120);
        notReleased.setReleaseDate(LocalDate.of(2026, 10, 2));
        assertCode("BUSINESS_RULE_VIOLATION", () -> ScreeningRules.plan(notReleased,
                time("2026-10-01T12:00:00+08:00"), time("2026-10-01T09:00:00+08:00"), false, NOW));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-01T12:00:00.001+08:00,2026-10-01T09:00:00+08:00",
            "2026-10-01T12:00:00+08:00,2026-10-01T09:00:00.001+08:00"
    })
    void fractionalBusinessSecondsAreRejectedInsteadOfRounded(String start, String saleStart) {
        assertCode("INVALID_ARGUMENT", () -> ScreeningRules.plan(movie(120), time(start), time(saleStart), false, NOW));
    }

    @Test
    void durationOverflowBeyondMysqlDateRangeIsAControlledBusinessError() {
        assertCode("BUSINESS_RULE_VIOLATION", () -> ScreeningRules.plan(movie(120),
                time("9999-12-31T23:00:00+08:00"), time("2026-10-01T09:00:00+08:00"), false, NOW));
    }

    @Test
    void offsetConversionOverflowIsAnInvalidInputRatherThanAnInternalError() {
        assertCode("INVALID_ARGUMENT", () -> ScreeningRules.local(
                time("+999999999-12-31T23:59:59-18:00")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void unknownOrNonPositiveMovieDurationCannotBeUsedToSchedule(Integer duration) {
        assertCode("BUSINESS_RULE_VIOLATION", () -> ScreeningRules.plan(movie(duration),
                time("2026-10-01T12:00:00+08:00"), time("2026-10-01T09:00:00+08:00"), false, NOW));
    }

    @Test
    void scheduledSalesMayBeginAtNowButNeverBeforeNowOrAtTheSalesCutoff() {
        ScreeningRules.Times atNow = ScreeningRules.plan(movie(120), time("2026-10-01T12:00:00+08:00"),
                time("2026-10-01T08:00:00+08:00"), false, NOW);
        assertEquals(NOW, atNow.saleStart());
        for (String sale : new String[]{"2026-10-01T07:59:59+08:00", "2026-10-01T11:45:00+08:00"}) {
            assertCode("BUSINESS_RULE_VIOLATION", () -> ScreeningRules.plan(movie(120),
                    time("2026-10-01T12:00:00+08:00"), time(sale), false, NOW));
        }
    }

    @Test
    void immediateSalesUseServerNowAndRejectClientSuppliedSaleStart() {
        ScreeningRules.Times immediate = ScreeningRules.plan(movie(120), time("2026-10-01T12:00:00+08:00"), null, true, NOW);
        assertEquals(NOW, immediate.saleStart());
        assertCode("INVALID_ARGUMENT", () -> ScreeningRules.plan(movie(120),
                time("2026-10-01T12:00:00+08:00"), time("2026-10-01T09:00:00+08:00"), true, NOW));
        assertCode("BUSINESS_RULE_VIOLATION", () -> ScreeningRules.plan(movie(120),
                time("2026-10-01T08:15:00+08:00"), null, true, NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "1.001", "100000000.00", "1e2"})
    void ticketPriceMustBePositiveAndExactlyRepresentableAsTheContractedDecimal(String price) {
        assertCode("INVALID_ARGUMENT", () -> ScreeningRules.parsePrice(price));
    }

    @Test
    void smallestAndLargestSupportedTicketPricesArePreservedExactly() {
        assertEquals(new BigDecimal("0.01"), ScreeningRules.parsePrice("0.01"));
        assertEquals(new BigDecimal("99999999.99"), ScreeningRules.parsePrice("99999999.99"));
    }

    @Test
    void admissionDefaultsAcrossMidnightAndPreservesAnEditedSchedule() {
        LocalDateTime start = LocalDateTime.parse("2026-10-02T00:05:00");
        LocalDateTime original = LocalDateTime.parse("2026-10-01T23:55:00");
        assertEquals(LocalDateTime.parse("2026-10-01T23:50:00"),
                ScreeningRules.admission(null, null, start));
        assertEquals(original, ScreeningRules.admission(null, original, start));
        assertEquals(original, ScreeningRules.admission(time("2026-10-01T15:55:00Z"), null, start));
        assertEquals(start, ScreeningRules.admission(time("2026-10-02T00:05:00+08:00"), null, start));
        assertCode("BUSINESS_RULE_VIOLATION", () -> ScreeningRules.admission(
                time("2026-10-02T00:05:01+08:00"), null, start));
        assertCode("INVALID_ARGUMENT", () -> ScreeningRules.admission(
                time("2026-10-02T00:04:00.001+08:00"), null, start));
        assertCode("BUSINESS_RULE_VIOLATION", () -> ScreeningRules.admission(null, original,
                LocalDateTime.parse("2026-10-01T23:54:00")));
    }

    private Movie movie(Integer duration) {
        Movie movie = new Movie();
        movie.setReleaseDate(LocalDate.of(2026, 10, 1));
        movie.setDurationMinutes(duration);
        return movie;
    }

    private OffsetDateTime time(String value) {
        return OffsetDateTime.parse(value);
    }

    private void assertCode(String code, Runnable operation) {
        assertEquals(code, assertThrows(ApplicationException.class, operation::run).getErrorCode().name());
    }
}
