package io.github.chenforegone.encoreticket.model.query;

import io.github.chenforegone.encoreticket.model.enums.TicketDisplayStatus;
import java.time.LocalDateTime;

/**
 * 已规范化且限定当前用户的票券查询条件。
 *
 * @author c1342
 */
public record TicketQueryFilter(long userId, TicketDisplayStatus status,
                                String movieTitlePrefix, String orderNo,
                                LocalDateTime now, long offset, int pageSize) {
}
