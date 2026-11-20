package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.Seat;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 排片初始化时读取物理座位。
 * @author c1342
 */
@Mapper
public interface SeatMapper extends BaseMapper<Seat> {
    /** 有效场次占售投影；普通读，不锁订单或场次。 */
    /**
     * 未释放的场次占售状态。
     * @author c1342
     */
    record Occupancy(Long seatId, Integer status) {}

    @org.apache.ibatis.annotations.Select("SELECT * FROM seat WHERE id=#{id} FOR UPDATE")
    Seat lockRow(@Param("id") long id);

    @org.apache.ibatis.annotations.Update("UPDATE seat SET status=#{seat.status}, version=version+1, update_time=#{seat.updateTime} WHERE id=#{seat.id} AND hall_id=#{seat.hallId} AND version=#{version}")
    int updateEdit(@Param("seat") Seat seat, @Param("version") int version);

    List<Seat> allInHall(@Param("hallId") long hallId);
    List<Occupancy> activeOccupancy(@Param("hallId") long hallId,
            @Param("now") java.time.LocalDateTime now);

    List<Seat> enabledInHall(@Param("hallId") long hallId);
}
