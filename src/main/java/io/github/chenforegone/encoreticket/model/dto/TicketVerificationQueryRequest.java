package io.github.chenforegone.encoreticket.model.dto;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 核销记录筛选，时间区间为左闭右开。
 *
 * @author c1342
 */
@Getter
@Setter
public class TicketVerificationQueryRequest {
    private String page = "1";
    private String pageSize = "20";
    private String screeningId;
    private String operatorId;
    private String ticketCode;
    private String deviceCode;
    private String verifiedAtFrom;
    private String verifiedAtTo;

    public static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) { throw invalid(); }
        try { return Long.parseLong(value); } catch (NumberFormatException e) { throw invalid(); }
    }

    public int pageNumber() {
        long value = id(page);
        if (value > Integer.MAX_VALUE) { throw invalid(); }
        return (int) value;
    }

    public int size() {
        long value = id(pageSize);
        if (value > 100) { throw invalid(); }
        return (int) value;
    }

    public Instant from() { return time(verifiedAtFrom); }
    public Instant to() { return time(verifiedAtTo); }

    public void validate() {
        pageNumber(); size();
        if (screeningId != null) { id(screeningId); }
        if (operatorId != null) { id(operatorId); }
        if (ticketCode != null && (ticketCode.isBlank() || ticketCode.length() > 128)) { throw invalid(); }
        if (deviceCode != null && (deviceCode.isBlank() || deviceCode.length() > 64)) { throw invalid(); }
        if (from() != null && to() != null && !from().isBefore(to())) { throw invalid(); }
    }

    private static Instant time(String value) {
        if (value == null) { return null; }
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})")) { throw invalid(); }
        try { return OffsetDateTime.parse(value).toInstant(); } catch (RuntimeException e) { throw invalid(); }
    }

    private static TicketVerificationException invalid() { return new TicketVerificationException(ErrorCode.INVALID_ARGUMENT); }
}
