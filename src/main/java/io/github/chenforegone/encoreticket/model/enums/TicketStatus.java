package io.github.chenforegone.encoreticket.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 票券持久化状态。
 *
 * @author c1342
 */
public enum TicketStatus {
    VALID(0), USED(1), VOID(2);

    @EnumValue
    private final Integer dbValue;

    TicketStatus(Integer dbValue) {
        this.dbValue = dbValue;
    }

    public Integer getDbValue() {
        return dbValue;
    }

    public String getApiCode() {
        return name();
    }

    public static TicketStatus fromValue(Integer value) {
        for (TicketStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未定义的票券状态值：" + value);
    }

    public static TicketStatus fromDbValue(Integer dbValue) {
        for (TicketStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }
        throw new IllegalStateException("票券持久化状态异常");
    }
}
