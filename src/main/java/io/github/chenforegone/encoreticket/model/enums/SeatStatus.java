package io.github.chenforegone.encoreticket.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 物理座位状态。
 *
 * <p>数据库保存 {@link #getValue()}，接口通过转换层返回 {@link #getCode()}。
 *
 * @author c1342
 */
@Getter
public enum SeatStatus {

    /** 启用，物理座位可正常使用。 */
    ENABLED(0, "ENABLED"),

    /** 停用，物理座位不可正常使用。 */
    DISABLED(1, "DISABLED");

    /** 数据库保存的 TINYINT 状态值。 */
    @EnumValue
    private final Integer value;

    /** 接口契约中的稳定状态字符串。 */
    private final String code;

    SeatStatus(Integer value, String code) {
        this.value = value;
        this.code = code;
    }

    /**
     * 将数据库状态值显式转换为物理座位状态。
     *
     * @param value 数据库中的状态值
     * @return 对应的物理座位状态
     * @throws IllegalArgumentException 状态值为空或未定义时抛出
     */
    public static SeatStatus fromValue(Integer value) {
        for (SeatStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未定义的物理座位状态值：" + value);
    }
}
