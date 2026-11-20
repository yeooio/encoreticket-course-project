package io.github.chenforegone.encoreticket.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.json.StrictIntegerDeserializer;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 影厅或物理座位状态命令的编辑版本。
 * @author c1342
 */
public record HallVersionRequest(
        @NotNull @Min(0) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer version) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
}
