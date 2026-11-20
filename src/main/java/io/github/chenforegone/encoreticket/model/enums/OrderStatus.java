package io.github.chenforegone.encoreticket.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 订单状态。
 *
 * @author c1342
 */
@Getter
public enum OrderStatus {
    WAIT_PAY(0), PAID(1), CANCELLED(2), EXPIRED(3), REFUNDING(4), REFUNDED(5);

    @EnumValue
    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public String getApiCode() {
        return this == WAIT_PAY ? "PENDING_PAYMENT" : name();
    }

    public static OrderStatus fromValue(Integer value) {
        for (OrderStatus status : values()) {
            if (value != null && status.code == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("未定义的订单状态值：" + value);
    }

    public int getCode() {
        return code;
    }
}
