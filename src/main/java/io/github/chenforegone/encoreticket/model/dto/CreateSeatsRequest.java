package io.github.chenforegone.encoreticket.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 一次原子追加 1～500 个固定位置。
 * @author c1342
 */
public record CreateSeatsRequest(@NotNull @Size(min=1, max=500) List<@NotNull @Valid SeatPositionRequest> seats) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) { throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT); }
}
