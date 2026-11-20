package io.github.chenforegone.encoreticket.service.impl;

import io.github.chenforegone.encoreticket.service.transaction.OrderExpirationProcessor;

import io.github.chenforegone.encoreticket.mapper.OrderMapper;
import io.github.chenforegone.encoreticket.model.entity.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 待支付订单的实际截止时间与有界到期释放。
 *
 * @author c1342
 */
@Service
public class OrderExpirationService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderExpirationService.class);
    private final OrderMapper orderMapper;
    private final OrderExpirationProcessor processor;
    private final Clock clock;
    private final int batchSize;
    private Instant afterExpiry;
    private Long afterId;

    public OrderExpirationService(OrderMapper orderMapper, OrderExpirationProcessor processor, Clock clock,
            @Value("${encore.order.expiration-scheduler.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Order expiration batch size must be between 1 and 1000");
        }
        this.orderMapper = orderMapper;
        this.processor = processor;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** 公开座位查询只检查当前场次的一批；未处理席位保持数据库中的 HELD 状态。 */
    public void expireForScreening(long screeningId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        process(orderMapper.selectDueOrders(screeningId, now, null, null, batchSize), now);
    }

    /** 游标跨轮前进，异常订单不会永久阻塞后续候选；扫完后回到起点重试。 */
    public synchronized void expireDueOrders() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        List<Order> candidates = orderMapper.selectDueOrders(null, now, afterExpiry, afterId, batchSize);
        if (candidates.isEmpty() && afterExpiry != null) {
            afterExpiry = null;
            afterId = null;
            candidates = orderMapper.selectDueOrders(null, now, null, null, batchSize);
        }
        if (!candidates.isEmpty()) {
            Order last = candidates.getLast();
            afterExpiry = last.getExpiresAt();
            afterId = last.getId();
        }
        process(candidates, now);
    }

    private void process(List<Order> candidates, Instant now) {
        for (Order order : candidates) {
            try {
                processor.expire(order.getId(), now);
            } catch (RuntimeException exception) {
                // SQL 和驱动消息可能包含数据；仅记录定位所需的内部标识与异常类型。
                LOG.warn("Order expiration failed: orderId={}, failureType={}",
                        order.getId(), exception.getClass().getName());
            }
        }
    }

    /** 订单最多占座十五分钟，售票截止时所有待支付订单同时截止。 */
    public static Instant calculateExpiresAt(Instant createdAt, LocalDateTime saleEndTime) {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(saleEndTime, "saleEndTime");
        Instant maximumHold = createdAt.plus(15, ChronoUnit.MINUTES);
        Instant salesCutoff = saleEndTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        return maximumHold.isBefore(salesCutoff) ? maximumHold : salesCutoff;
    }
}
