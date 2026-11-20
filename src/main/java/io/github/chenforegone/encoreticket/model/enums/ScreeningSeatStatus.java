package io.github.chenforegone.encoreticket.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 场次座位销售状态。
 *
 * <p>数据库保存 {@link #getValue()}，接口通过转换层返回 {@link #getCode()}。
 *
 * @author c1342
 */
@Getter
public enum ScreeningSeatStatus {

    /** 可售，尚未被订单占用或售出。 */
    AVAILABLE(0, "AVAILABLE"),

    /** 由待支付订单临时占用。 */
    HELD(1, "HELD"),

    /** 已售出。 */
    SOLD(2, "SOLD"),

    /** 禁售。 */
    DISABLED(3, "DISABLED");

    /** 数据库保存的 TINYINT 状态值。 */
    @EnumValue
    private final Integer value;

    /** 接口契约中的稳定状态字符串。 */
    private final String code;

    ScreeningSeatStatus(Integer value, String code) {
        this.value = value;
        this.code = code;
    }

    /**
     * 将数据库状态值显式转换为场次座位销售状态。
     *
     * @param value 数据库中的状态值
     * @return 对应的场次座位销售状态
     * @throws IllegalArgumentException 状态值为空或未定义时抛出
     */
    public static ScreeningSeatStatus fromValue(Integer value) {
        for (ScreeningSeatStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未定义的场次座位销售状态值：" + value);
    }
}
