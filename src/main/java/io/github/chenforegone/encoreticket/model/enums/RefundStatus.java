package io.github.chenforegone.encoreticket.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 退款状态。
 *
 * @author c1342
 */
@Getter
public enum RefundStatus {
    PENDING(0), SUCCESS(1), FAILED(2);

    @EnumValue
    private final int code;

    RefundStatus(int code) { this.code = code; }

    public static RefundStatus fromValue(Integer value) {
        for (RefundStatus status : values()) {
            if (value != null && status.code == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("未定义的退款状态值：" + value);
    }
}
