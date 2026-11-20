package io.github.chenforegone.encoreticket.repository;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.ConflictException;
import io.github.chenforegone.encoreticket.gateway.PaymentChannelEvent;
import io.github.chenforegone.encoreticket.mapper.PaymentSettlementMapper;
import io.github.chenforegone.encoreticket.model.entity.Ticket;
import io.github.chenforegone.encoreticket.model.enums.TicketStatus;
import org.springframework.stereotype.Repository;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 支付专用跨聚合持久化；调用方先持有订单行锁，座位始终按主键升序锁定。
 *
 * @author c1342
 */
@Repository
public class PaymentSettlementRepository {
    private final PaymentSettlementMapper mapper;

    public PaymentSettlementRepository(PaymentSettlementMapper mapper) {
        this.mapper = mapper;
    }

    public Instant deadline(long orderId) {
        PaymentSettlementMapper.DeadlineRow row = mapper.selectDeadline(orderId);
        Instant created = row.createTime().toInstant(ZoneOffset.UTC).plusSeconds(900);
        Instant expires = row.expiresAt().toInstant(ZoneOffset.UTC);
        Instant sale = row.saleEndTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        return created.isBefore(expires) ? (created.isBefore(sale) ? created : sale)
                : (expires.isBefore(sale) ? expires : sale);
    }

    public String attempt(long paymentId) {
        return mapper.selectAttempt(paymentId);
    }

    public void attempt(long paymentId, String attempt) {
        check(mapper.updateAttempt(paymentId, attempt));
    }

    public boolean knownAttempt(long paymentId, String attempt) {
        return mapper.countKnownAttempt(paymentId, attempt) > 0;
    }

    public void orderState(long id, int version, int before, int after, Instant now) {
        check(mapper.updateOrderState(id, version, before, after, now));
    }

    public void settleSeats(long orderId, boolean sold, Instant now) {
        List<Long> seats = mapper.selectSeatIds(orderId);
        for (Long seat : seats) {
            if (mapper.lockSeat(seat) == null) {
                throw new ConflictException(ErrorCode.PAYMENT_STATE_CONFLICT);
            }
        }
        if (sold) {
            List<Long> items = mapper.selectItemSeatIds(orderId);
            if (items.isEmpty() || !items.equals(seats)) {
                throw new ConflictException(ErrorCode.PAYMENT_STATE_CONFLICT);
            }
        }
        for (Long seat : seats) {
            check(mapper.updateSeatState(seat, orderId, sold ? 2 : 0, sold ? orderId : null, now));
        }
    }

    public void issueTickets(long orderId, Instant now) {
        List<Long> items = mapper.selectOrderItemIds(orderId);
        if (items.isEmpty()) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE_CONFLICT);
        }
        for (Long item : items) {
            Ticket ticket = new Ticket();
            ticket.setId(IdWorker.getId());
            ticket.setStatus(TicketStatus.VALID);
            ticket.setOrderItemId(item);
            ticket.setTicketCode(UUID.randomUUID().toString().replace("-", ""));
            ticket.setCreateTime(now);
            ticket.setUpdateTime(now);
            check(mapper.insertTicket(ticket));
        }
    }

    public void notification(PaymentChannelEvent event, byte[] hash, Instant now) {
        mapper.insertNotificationIfAbsent(IdWorker.getId(), event.provider(), event.eventId(), event.paymentNo(),
                event.eventType(), hash, now, now, event.attemptId());
        PaymentSettlementMapper.NotificationHashRow existing =
                mapper.selectNotificationHashForUpdate(event.provider(), event.eventId());
        if (existing == null || !MessageDigest.isEqual(hash, existing.payloadHash())) {
            throw new ConflictException(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
        }
    }

    public byte[] notificationHash(String provider, String eventId) {
        PaymentSettlementMapper.NotificationHashRow row = mapper.selectNotificationHash(provider, eventId);
        return row == null ? null : row.payloadHash();
    }

    public void refundIntent(long refundId, Instant now) {
        check(mapper.insertRefundAttempt(refundId, now));
        check(mapper.insertRefundAudit(IdWorker.getId(), refundId, now));
    }

    public static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public static void check(int count) {
        if (count != 1) {
            throw new ConflictException(ErrorCode.PAYMENT_STATE_CONFLICT);
        }
    }
}
