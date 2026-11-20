package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.Order;
import io.github.chenforegone.encoreticket.model.entity.ScreeningSeat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 订单过期状态与占座释放的数据访问边界。
 *
 * @author c1342
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM orders WHERE id=#{id} FOR UPDATE")
    Order lockById(@Param("id") long id);


    List<Order> selectDueOrders(@Param("screeningId") Long screeningId, @Param("now") Instant now,
                               @Param("afterExpiry") Instant afterExpiry, @Param("afterId") Long afterId,
                               @Param("limit") int limit);

    int expireIfDue(@Param("orderId") long orderId, @Param("now") Instant now);

    List<Long> selectOrderItemSeatIds(@Param("orderId") long orderId);

    List<ScreeningSeat> selectClaimedSeatsForUpdate(@Param("orderId") long orderId);

    int releaseHeldSeats(@Param("orderId") long orderId, @Param("screeningId") long screeningId,
                         @Param("now") Instant now);
}
