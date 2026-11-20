package io.github.chenforegone.encoreticket.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 影厅状态。
 *
 * <p>数据库保存 {@link #getValue()}，接口通过转换层返回 {@link #getCode()}。
 *
 * @author c1342
 */
@Getter
public enum HallStatus {

    /** 启用，可用于正常排片。 */
    ENABLED(0, "ENABLED"),

    /** 停用。 */
    DISABLED(1, "DISABLED");

    /** 数据库保存的 TINYINT 状态值。 */
    @EnumValue
    private final Integer value;

    /** 接口契约中的稳定状态字符串。 */
    private final String code;

    HallStatus(Integer value, String code) {
        this.value = value;
        this.code = code;
    }

    /**
     * 将数据库状态值显式转换为影厅状态。
     *
     * @param value 数据库中的状态值
     * @return 对应的影厅状态
     * @throws IllegalArgumentException 状态值为空或未定义时抛出
     */
    public static HallStatus fromValue(Integer value) {
        for (HallStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未定义的影厅状态值：" + value);
    }
}
