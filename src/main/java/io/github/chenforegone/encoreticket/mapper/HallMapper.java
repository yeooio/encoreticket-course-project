package io.github.chenforegone.encoreticket.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.Hall;

/**
 * 影厅排片共同版本数据访问。
 * @author c1342
 */
@Mapper
public interface HallMapper extends BaseMapper<Hall> {
    /** 仅锁定主键对应的 Hall 行；调用方必须持有事务。 */
    @org.apache.ibatis.annotations.Select("SELECT * FROM hall WHERE id = #{id} FOR UPDATE")
    Hall lockRow(@Param("id") long id);

    @org.apache.ibatis.annotations.Update("UPDATE hall SET hall_name=#{hall.hallName}, status=#{hall.status}, version=version+1, update_time=#{hall.updateTime} WHERE id=#{hall.id} AND version=#{version}")
    int updateEdit(@Param("hall") Hall hall, @Param("version") int version);

    @org.apache.ibatis.annotations.Select("SELECT EXISTS(SELECT 1 FROM screening WHERE hall_id=#{hallId})")
    boolean hasScreenings(@Param("hallId") long hallId);

    long countFiltered(@Param("q") io.github.chenforegone.encoreticket.model.dto.HallQueryRequest query);
    java.util.List<Hall> listFiltered(@Param("q") io.github.chenforegone.encoreticket.model.dto.HallQueryRequest query,
            @Param("offset") long offset, @Param("limit") int limit);

    /**
     * 每厅批量统计，无布局信息。
     * @author c1342
     */
    record SeatCounts(Long hallId, Long total, Long enabled) {}
    java.util.List<SeatCounts> seatCounts(@Param("ids") java.util.List<Long> ids);
    java.util.List<Long> hallsWithScreenings(@Param("ids") java.util.List<Long> ids);

    int claimSchedule(@Param("id") long id, @Param("version") long version);
}
