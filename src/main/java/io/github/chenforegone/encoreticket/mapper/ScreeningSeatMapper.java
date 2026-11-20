package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.ScreeningSeat;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 场次座位快照初始化和换厅重建。
 * @author c1342
 */
@Mapper
public interface ScreeningSeatMapper extends BaseMapper<ScreeningSeat> {
    int insertSeats(@Param("seats") List<ScreeningSeat> seats);
    int deleteForScreening(@Param("screeningId") long screeningId);
}
