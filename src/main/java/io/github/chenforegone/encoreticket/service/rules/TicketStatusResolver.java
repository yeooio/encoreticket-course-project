package io.github.chenforegone.encoreticket.service.rules;

import io.github.chenforegone.encoreticket.model.enums.OrderStatus;
import io.github.chenforegone.encoreticket.model.enums.TicketDisplayStatus;
import io.github.chenforegone.encoreticket.model.enums.TicketStatus;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 无时钟与数据库副作用的票券展示状态规则。
 *
 * @author c1342
 */
@Component
public class TicketStatusResolver {
    public TicketDisplayStatus resolve(Integer ticketStatus, Integer orderStatus,
                                       LocalDateTime endTime, LocalDateTime now) {
        TicketStatus ticket = TicketStatus.fromDbValue(ticketStatus);
        OrderStatus order = null;
        for (OrderStatus candidate : OrderStatus.values()) {
            if (Integer.valueOf(candidate.getCode()).equals(orderStatus)) {
                order = candidate;
                break;
            }
        }
        if (order == null || endTime == null || now == null) {
            throw new IllegalStateException("票券状态关联数据异常");
        }
        if (ticket == TicketStatus.USED) {
            return TicketDisplayStatus.USED;
        }
        if (ticket == TicketStatus.VOID) {
            return TicketDisplayStatus.VOID;
        }
        if (order == OrderStatus.REFUNDING) {
            return TicketDisplayStatus.REFUNDING;
        }
        if (order != OrderStatus.PAID) {
            throw new IllegalStateException("有效票券与订单状态不一致");
        }
        return endTime.isAfter(now) ? TicketDisplayStatus.VALID : TicketDisplayStatus.EXPIRED;
    }
}
