package io.github.chenforegone.encoreticket.service.rules;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.BusinessRuleException;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.model.entity.Movie;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 场次输入及影院时间规则，不依赖服务器默认时区。
 * @author c1342
 */
public final class ScreeningRules {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int TURNAROUND_MINUTES = 15;

    private ScreeningRules() {
    }

    public static long parseId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    public static BigDecimal parsePrice(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,7})(?:\\.[0-9]{1,2})?")) {
            throw invalid();
        }
        BigDecimal price = new BigDecimal(value);
        if (price.signum() <= 0) {
            throw invalid();
        }
        return price.setScale(2);
    }

    public static LocalDateTime now(Clock clock) {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.SECONDS), ZONE);
    }

    public static LocalDateTime local(OffsetDateTime value) {
        if (value == null || value.getNano() != 0) {
            throw invalid();
        }
        try {
            LocalDateTime local = value.atZoneSameInstant(ZONE).toLocalDateTime();
            if (local.getYear() < 1000 || local.getYear() > 9999) {
                throw invalid();
            }
            return local;
        } catch (DateTimeException exception) {
            throw invalid();
        }
    }

    public static Times plan(Movie movie, OffsetDateTime start, OffsetDateTime saleStart,
            boolean immediate, LocalDateTime now) {
        LocalDateTime startTime = local(start);
        if (movie.getDurationMinutes() == null || movie.getDurationMinutes() <= 0
                || startTime.toLocalDate().isBefore(movie.getReleaseDate())) {
            throw timeInvalid();
        }
        if (immediate && saleStart != null || !immediate && saleStart == null) {
            throw invalid();
        }
        try {
            LocalDateTime end = startTime.plusMinutes(movie.getDurationMinutes());
            LocalDateTime cutoff = startTime.minusMinutes(15);
            LocalDateTime sale = immediate ? now : local(saleStart);
            if (end.plusMinutes(TURNAROUND_MINUTES).getYear() > 9999
                    || cutoff.getYear() < 1000 || !cutoff.isAfter(now)
                    || sale.isBefore(now) || !sale.isBefore(cutoff)) {
                throw timeInvalid();
            }
            return new Times(startTime, end, sale, cutoff, movie.getDurationMinutes());
        } catch (DateTimeException | ArithmeticException exception) {
            throw timeInvalid();
        }
    }

    /** 创建时默认提前15分钟；编辑时保留原配置，显式输入统一按影院时区校验。 */
    public static LocalDateTime admission(OffsetDateTime requested, LocalDateTime existing,
            LocalDateTime start) {
        LocalDateTime result = requested == null
                ? (existing == null ? start.minusMinutes(15) : existing) : local(requested);
        if (result.getYear() < 1000 || result.getYear() > 9999 || result.isAfter(start)) {
            throw timeInvalid();
        }
        return result;
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }

    private static BusinessRuleException timeInvalid() {
        return new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION);
    }

    /**
     * 服务端计算的放映和售票时间。
     * @author c1342
     */
    public record Times(LocalDateTime start, LocalDateTime end, LocalDateTime saleStart,
            LocalDateTime saleEnd, int duration) {
    }
}
