package io.github.chenforegone.encoreticket.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.json.StrictIntegerDeserializer;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 场次状态操作的乐观版本。
 *
 * @author c1342
 */
public record ScreeningVersionRequest(
        @NotNull(message = "版本不能为空") @Min(value = 0, message = "版本不能为负数")
        @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer version
) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
}


