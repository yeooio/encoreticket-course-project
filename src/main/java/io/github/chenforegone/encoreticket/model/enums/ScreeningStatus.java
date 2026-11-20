package io.github.chenforegone.encoreticket.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 场次状态。
 *
 * @author c1342
 */
public enum ScreeningStatus {
    WAIT_SALE(0), ON_SALE(1), OFF_SALE(2), CLOSED(3), ENDED(4);

    @EnumValue
    private final int code;

    ScreeningStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
