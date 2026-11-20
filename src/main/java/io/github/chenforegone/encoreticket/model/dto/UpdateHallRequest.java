package io.github.chenforegone.encoreticket.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.json.StrictIntegerDeserializer;
import io.github.chenforegone.encoreticket.common.json.StrictStringDeserializer;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 仅允许修改影厅名称。
 * @author c1342
 */
public record UpdateHallRequest(
        @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String hallName,
        @NotNull @Min(0) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer version) {
    public UpdateHallRequest {
        hallName = CreateHallRequest.validatedText(hallName, 64);
    }
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
}
