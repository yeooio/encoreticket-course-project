package io.github.chenforegone.encoreticket.service.impl;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.converter.TicketConverter;
import io.github.chenforegone.encoreticket.mapper.TicketMapper;
import io.github.chenforegone.encoreticket.model.dto.TicketQueryRequest;
import io.github.chenforegone.encoreticket.model.enums.TicketDisplayStatus;
import io.github.chenforegone.encoreticket.model.query.TicketProjection;
import io.github.chenforegone.encoreticket.model.query.TicketQueryFilter;
import io.github.chenforegone.encoreticket.model.vo.TicketDetailVO;
import io.github.chenforegone.encoreticket.model.vo.TicketStatusVO;
import io.github.chenforegone.encoreticket.model.vo.TicketSummaryVO;
import io.github.chenforegone.encoreticket.service.TicketService;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

/**
 * 本人范围内的只读票券查询编排。
 *
 * @author c1342
 */
@Service
public class TicketServiceImpl implements TicketService {
    private static final ZoneId CINEMA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");
    private final TicketMapper mapper;
    private final TicketConverter converter;
    private final Validator validator;
    private final Clock clock;

    public TicketServiceImpl(TicketMapper mapper, TicketConverter converter, Validator validator, Clock clock) {
        this.mapper = mapper;
        this.converter = converter;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    public PageResponse<TicketSummaryVO> list(String userId, TicketQueryRequest request) {
        long owner = parseId(userId);
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
        LocalDateTime now = now();
        TicketDisplayStatus status = request.getStatus() == null ? null
                : TicketDisplayStatus.valueOf(request.getStatus());
        long offset = ((long) request.getPage() - 1) * request.getPageSize();
        TicketQueryFilter filter = new TicketQueryFilter(owner, status, request.getMovieTitle(),
                request.getOrderNo(), now, offset, request.getPageSize());
        TicketQueryFilter integrityFilter = new TicketQueryFilter(owner, null, request.getMovieTitle(),
                request.getOrderNo(), now, offset, request.getPageSize());
        if (mapper.hasInvalidOwned(integrityFilter)) {
            throw new IllegalStateException("票券关联数据异常");
        }
        long total = mapper.count(filter);
        var items = mapper.selectPage(filter).stream().map(ticket -> {
            requireOwner(ticket, owner);
            validateProjection(ticket);
            return converter.toSummary(ticket, now);
        }).toList();
        return new PageResponse<>(items, request.getPage(), request.getPageSize(), total);
    }

    @Override
    public TicketDetailVO getDetail(String userId, String ticketId) {
        long owner = parseId(userId);
        long id = parseId(ticketId);
        LocalDateTime now = now();
        return converter.toDetail(owned(id, owner), now);
    }

    @Override
    public TicketStatusVO getStatus(String userId, String ticketId) {
        long owner = parseId(userId);
        long id = parseId(ticketId);
        LocalDateTime now = now();
        return converter.toStatus(owned(id, owner), now);
    }

    private TicketProjection owned(long id, long owner) {
        TicketProjection ticket = mapper.selectOwned(id, owner);
        requireOwner(ticket, owner);
        validateProjection(ticket);
        if (ticket.id() != id) {
            throw new IllegalStateException("票券查询标识不一致");
        }
        return ticket;
    }

    private static void requireOwner(TicketProjection ticket, long owner) {
        if (ticket == null || (ticket.userId() != null && ticket.userId() != owner)) {
            throw new ResourceNotFoundException();
        }
    }

    private static void validateProjection(TicketProjection ticket) {
        if (ticket.id() == null || ticket.orderItemId() == null || ticket.orderId() == null
                || ticket.userId() == null || ticket.movieId() == null || ticket.screeningId() == null
                || ticket.hallId() == null || ticket.screeningSeatId() == null || ticket.seatId() == null
                || ticket.ticketCode() == null || ticket.orderNo() == null || ticket.movieTitle() == null
                || ticket.hallName() == null || ticket.ticketStatus() == null || ticket.orderStatus() == null
                || ticket.rowNumber() == null || ticket.seatNumber() == null || ticket.startTime() == null
                || ticket.endTime() == null || ticket.unitPrice() == null || ticket.createdAt() == null) {
            throw new IllegalStateException("票券必要关联或字段缺失");
        }
        int state = ticket.ticketStatus();
        int order = ticket.orderStatus();
        if (state < 0 || state > 2 || order < 0 || order > 5
                || (state == 0 && order != 1 && order != 4)
                || ticket.unitPrice().signum() < 0 || ticket.unitPrice().compareTo(MAX_PRICE) > 0
                || ticket.unitPrice().stripTrailingZeros().scale() > 2) {
            throw new IllegalStateException("票券状态或价格异常");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), CINEMA_ZONE);
    }

    private static long parseId(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
