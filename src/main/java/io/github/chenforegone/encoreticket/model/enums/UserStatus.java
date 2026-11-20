package io.github.chenforegone.encoreticket.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 用户状态。
 *
 * <p>数据库用 TINYINT 保存 {@link #getDbValue()}，接口返回 {@link #getApiCode()}。
 * 数据库中未定义的状态值由 {@link #fromDbValue(Integer)} 报告为数据异常，不静默降级为默认状态。
 *
 * @author c1342
 */
@Getter
public enum UserStatus {

    /** 正常，可以登录和使用系统。 */
    NORMAL(0, "NORMAL"),

    /** 禁用，不能登录，已签发的令牌会被清除。 */
    DISABLED(1, "DISABLED");

    /** 数据库保存的状态值。
     * -- GETTER --
     *  获取数据库状态值。
     *
     * @return 数据库保存的 TINYINT 值
     */
    @EnumValue
    private final Integer dbValue;

    /** 接口返回的状态字符串。
     * -- GETTER --
     *  获取接口状态字符串。
     *
     * @return 对外契约中的状态字符串
     */
    private final String apiCode;

    UserStatus(Integer dbValue, String apiCode) {
        this.dbValue = dbValue;
        this.apiCode = apiCode;
    }

    /**
     * 由数据库值解析用户状态。
     *
     * @param dbValue 数据库中的状态值
     * @return 对应的用户状态
     * @throws IllegalArgumentException 数据库值未定义时抛出
     */
    public static UserStatus fromDbValue(Integer dbValue) {
        for (UserStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未定义的用户状态值：" + dbValue);
    }
}
