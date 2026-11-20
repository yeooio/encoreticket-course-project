package io.github.chenforegone.encoreticket.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.github.chenforegone.encoreticket.common.json.StrictStringDeserializer;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/**
 * 场次座位下单输入，金额及归属由服务端确定。
 * @author c1342
 */
public record CreateOrderRequest(
        @JsonDeserialize(using=StrictStringDeserializer.class) String screeningId,
        @JsonDeserialize(contentUsing=StrictStringDeserializer.class) List<String> screeningSeatIds) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
}
