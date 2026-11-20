package io.github.chenforegone.encoreticket.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.json.StrictIntegerDeserializer;
import io.github.chenforegone.encoreticket.common.json.StrictOffsetDateTimeDeserializer;
import io.github.chenforegone.encoreticket.common.json.StrictStringDeserializer;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.OffsetDateTime;

/**
 * 替换待开售场次的可编辑字段。
 *
 * @author c1342
 */
public record UpdateScreeningRequest(
        @NotBlank(message = "电影 ID 不能为空")
        @Pattern(regexp = "[1-9][0-9]{0,18}", message = "电影 ID 必须为正整数")
        @DecimalMax(value = "9223372036854775807", message = "电影 ID 超出范围")
        @JsonDeserialize(using = StrictStringDeserializer.class) String movieId,
        @NotBlank(message = "影厅 ID 不能为空")
        @Pattern(regexp = "[1-9][0-9]{0,18}", message = "影厅 ID 必须为正整数")
        @DecimalMax(value = "9223372036854775807", message = "影厅 ID 超出范围")
        @JsonDeserialize(using = StrictStringDeserializer.class) String hallId,
        @NotBlank(message = "票价不能为空")
        @Pattern(regexp = "(?:0|[1-9][0-9]{0,7})(?:\\.[0-9]{1,2})?", message = "票价必须为最多两位小数的金额字符串")
        @DecimalMin(value = "0.01", message = "票价必须大于零")
        @JsonDeserialize(using = StrictStringDeserializer.class) String price,
        @NotNull(message = "放映开始时间不能为空") @JsonDeserialize(using = StrictOffsetDateTimeDeserializer.class) OffsetDateTime startTime,
        @NotNull(message = "售票开始时间不能为空") @JsonDeserialize(using = StrictOffsetDateTimeDeserializer.class) OffsetDateTime saleStartTime,
        @JsonDeserialize(using = StrictOffsetDateTimeDeserializer.class) OffsetDateTime admissionOpenTime,
        @NotNull(message = "版本不能为空") @Min(value = 0, message = "版本不能为负数")
        @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer version
) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
}
