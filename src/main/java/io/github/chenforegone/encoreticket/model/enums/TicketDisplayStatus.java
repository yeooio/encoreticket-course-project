package io.github.chenforegone.encoreticket.model.enums;

/**
 * 票券查询展示状态，不参与持久化。
 *
 * @author c1342
 */
public enum TicketDisplayStatus {
    VALID, USED, VOID, EXPIRED, REFUNDING;

    public String getApiCode() {
        return name();
    }
}
