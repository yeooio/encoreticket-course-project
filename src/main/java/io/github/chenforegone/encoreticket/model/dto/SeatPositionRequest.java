package io.github.chenforegone.encoreticket.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.json.StrictIntegerDeserializer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 创建后不可修改的物理座位坐标。
 * @author c1342
 */
public record SeatPositionRequest(
        @NotNull @Positive @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer rowNumber,
        @NotNull @Positive @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer seatNumber) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
}
