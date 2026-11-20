package io.github.chenforegone.encoreticket.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.json.StrictStringDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 影厅及其非空初始布局的原子创建命令。
 * @author c1342
 */
public record CreateHallRequest(
        @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String hallNo,
        @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String hallName,
        @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid SeatPositionRequest> seats) {
    public CreateHallRequest {
        hallNo = validatedText(hallNo, 32);
        hallName = validatedText(hallName, 64);
    }
    static String validatedText(String value, int max) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > max
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        return normalized;
    }
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
}
