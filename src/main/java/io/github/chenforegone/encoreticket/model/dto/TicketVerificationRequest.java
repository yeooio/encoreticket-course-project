package io.github.chenforegone.encoreticket.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;
import lombok.Getter;
import tools.jackson.databind.JsonNode;

/**
 * 严格字符串类型的扫码请求，保留票码原文。
 *
 * @author c1342
 */
@Getter
public class TicketVerificationRequest {
    private String ticketCode;
    private String screeningId;

    @JsonSetter("ticketCode")
    public void readTicketCode(JsonNode value) { ticketCode = text(value); }

    @JsonSetter("screeningId")
    public void readScreeningId(JsonNode value) { screeningId = text(value); }

    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) { throw new TicketVerificationException(ErrorCode.INVALID_ARGUMENT); }

    private static String text(JsonNode value) {
        if (!value.isString()) { throw new TicketVerificationException(ErrorCode.INVALID_ARGUMENT); }
        return value.asString();
    }

    public long validatedScreeningId() {
        if (ticketCode == null || ticketCode.isBlank() || ticketCode.length() > 128) {
            throw new TicketVerificationException(ErrorCode.INVALID_ARGUMENT);
        }
        return TicketVerificationQueryRequest.id(screeningId);
    }
}
