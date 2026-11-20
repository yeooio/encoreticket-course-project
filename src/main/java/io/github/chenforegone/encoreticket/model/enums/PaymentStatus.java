/**
 * 支付状态。
 *
 * @author c1342
 */
package io.github.chenforegone.encoreticket.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum PaymentStatus {
    PENDING(0, "PENDING"),
    SUCCESS(1, "SUCCESS"),
    FAILED(2, "FAILED");

    @EnumValue
    private final int code;
    private final String value;

    PaymentStatus(int code, String value) {
        this.code = code;
        this.value = value;
    }

    public static PaymentStatus fromValue(Integer value) {
        for (PaymentStatus status : values()) {
            if (value != null && status.code == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("未定义的支付状态值：" + value);
    }

    public int getCode() {
        return code;
    }

    public String getValue() {
        return value;
    }
}
