package io.github.chenforegone.encoreticket.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;

/**
 * 创建场次时的开售意图，不作为持久化状态。
 *
 * @author c1342
 */
public enum ScreeningSaleMode {
    SCHEDULED, IMMEDIATE;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ScreeningSaleMode fromJson(Object value) {
        if (value instanceof String text) {
            for (ScreeningSaleMode mode : values()) {
                if (mode.name().equals(text)) {
                    return mode;
                }
            }
        }
        throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
}
