package io.github.chenforegone.encoreticket.service.transaction;

import io.github.chenforegone.encoreticket.mapper.OrderMapper;
import io.github.chenforegone.encoreticket.model.entity.Order;
import io.github.chenforegone.encoreticket.model.entity.ScreeningSeat;
import io.github.chenforegone.encoreticket.model.enums.ScreeningSeatStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 每个待支付订单独立提交状态推进和占座释放，避免一条异常拖住整批。
 *
 * @author c1342
 */
@Service
public class OrderExpirationProcessor {

    private final OrderMapper orderMapper;

    public OrderExpirationProcessor(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /** 条件更新订单行先于座位写入，与后续支付处理采用相同的锁顺序。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean expire(long orderId, Instant now) {
        if (orderMapper.expireIfDue(orderId, now) == 0) {
            return false;
        }
        Order order = orderMapper.selectById(orderId);
        List<Long> itemSeatIds = orderMapper.selectOrderItemSeatIds(orderId);
        List<ScreeningSeat> claimedSeats = orderMapper.selectClaimedSeatsForUpdate(orderId);
        Set<Long> expected = new HashSet<>(itemSeatIds);
        if (expected.isEmpty() || expected.size() != itemSeatIds.size()
                || claimedSeats.size() != expected.size()
                || claimedSeats.stream().anyMatch(seat -> !expected.contains(seat.getId())
                    || !Objects.equals(seat.getScreeningId(), order.getScreeningId())
                    || !Objects.equals(seat.getOrderId(), orderId)
                    || seat.getStatus() != ScreeningSeatStatus.HELD)) {
            throw new IllegalStateException("Order items and held seats are inconsistent");
        }
        if (orderMapper.releaseHeldSeats(orderId, order.getScreeningId(), now) != expected.size()) {
            throw new IllegalStateException("Held seats changed during order expiration");
        }
        return true;
    }
}
