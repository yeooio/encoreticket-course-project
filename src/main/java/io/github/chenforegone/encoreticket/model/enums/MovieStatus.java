package io.github.chenforegone.encoreticket.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 电影目录状态。
 *
 * @author c1342
 */
public enum MovieStatus {

    /** 对游客公开。 */
    ONLINE(0, "ONLINE"),
    /** 不对游客公开，可能等待自动发行或已被管理员下架。 */
    OFFLINE(1, "OFFLINE");

    @EnumValue
    private final int value;
    private final String code;

    MovieStatus(int value, String code) {
        this.value = value;
        this.code = code;
    }

    public int getValue() {
        return value;
    }

    public String getCode() {
        return code;
    }
}
