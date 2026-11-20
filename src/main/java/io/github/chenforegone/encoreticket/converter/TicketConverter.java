package io.github.chenforegone.encoreticket.converter;

import io.github.chenforegone.encoreticket.model.enums.OrderStatus;
import io.github.chenforegone.encoreticket.model.query.TicketProjection;
import io.github.chenforegone.encoreticket.model.vo.TicketDetailVO;
import io.github.chenforegone.encoreticket.model.vo.TicketSummaryVO;
import io.github.chenforegone.encoreticket.model.vo.TicketStatusVO;
import io.github.chenforegone.encoreticket.service.rules.TicketStatusResolver;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 票券查询投影到接口视图的显式转换。
 *
 * @author c1342
 */
@Component
public class TicketConverter {
    private static final ZoneId CINEMA_ZONE = ZoneId.of("Asia/Shanghai");
    private final TicketStatusResolver resolver;

    public TicketConverter(TicketStatusResolver resolver) {
        this.resolver = resolver;
    }

    public TicketDetailVO toDetail(TicketProjection ticket, LocalDateTime now) {
        validateProjection(ticket);
        String status = resolver.resolve(ticket.ticketStatus(), ticket.orderStatus(), ticket.endTime(), now).getApiCode();
        return new TicketDetailVO(ticket.id().toString(), ticket.ticketCode(), status,
                ticket.orderId().toString(), ticket.orderNo(), orderApiCode(ticket.orderStatus()),
                ticket.movieId().toString(), ticket.movieTitle(), ticket.screeningId().toString(),
                ticket.hallId().toString(), ticket.hallName(), businessTime(ticket.startTime()),
                businessTime(ticket.endTime()), ticket.rowNumber(), ticket.seatNumber(),
                ticket.unitPrice().toPlainString(), "CNY",
                ticket.verifiedAt() == null ? null : ticket.verifiedAt().toString(), ticket.createdAt().toString());
    }

    public TicketSummaryVO toSummary(TicketProjection ticket, LocalDateTime now) {
        validateProjection(ticket);
        String status = resolver.resolve(ticket.ticketStatus(), ticket.orderStatus(), ticket.endTime(), now).getApiCode();
        return new TicketSummaryVO(ticket.id().toString(), ticket.orderId().toString(),
                ticket.movieId().toString(), ticket.screeningId().toString(), ticket.orderNo(),
                ticket.movieTitle(), ticket.hallName(), businessTime(ticket.startTime()),
                businessTime(ticket.endTime()), ticket.rowNumber(), ticket.seatNumber(), status,
                ticket.verifiedAt() == null ? null : ticket.verifiedAt().toString());
    }

    public TicketStatusVO toStatus(TicketProjection ticket, LocalDateTime now) {
        validateProjection(ticket);
        return new TicketStatusVO(resolver.resolve(ticket.ticketStatus(), ticket.orderStatus(), ticket.endTime(), now).getApiCode());
    }

    private static void validateProjection(TicketProjection ticket) {
        if (ticket == null || ticket.id() == null || ticket.orderItemId() == null
                || ticket.orderId() == null || ticket.userId() == null || ticket.movieId() == null
                || ticket.screeningId() == null || ticket.hallId() == null
                || ticket.screeningSeatId() == null || ticket.seatId() == null
                || ticket.ticketCode() == null || ticket.orderNo() == null
                || ticket.movieTitle() == null || ticket.hallName() == null
                || ticket.ticketStatus() == null || ticket.orderStatus() == null
                || ticket.rowNumber() == null || ticket.seatNumber() == null
                || ticket.startTime() == null || ticket.endTime() == null
                || ticket.unitPrice() == null || ticket.createdAt() == null) {
            throw new IllegalStateException("票券必要关联或字段缺失");
        }
    }

    private static String businessTime(LocalDateTime value) {
        return value.atZone(CINEMA_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String orderApiCode(Integer code) {
        for (OrderStatus status : OrderStatus.values()) {
            if (Integer.valueOf(status.getCode()).equals(code)) {
                return status == OrderStatus.WAIT_PAY ? "PENDING_PAYMENT" : status.name();
            }
        }
        throw new IllegalStateException("订单状态异常");
    }
}
