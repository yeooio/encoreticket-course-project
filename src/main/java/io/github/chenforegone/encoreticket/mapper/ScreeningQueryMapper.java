package io.github.chenforegone.encoreticket.mapper;

import io.github.chenforegone.encoreticket.model.query.ScreeningProjection;
import io.github.chenforegone.encoreticket.model.query.ScreeningQueryFilter;
import io.github.chenforegone.encoreticket.model.query.ScreeningSeatProjection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 场次展示数据的批量关联查询。
 *
 * @author c1342
 */
@Mapper
public interface ScreeningQueryMapper {
    long countPublic(@Param("q") ScreeningQueryFilter query, @Param("now") LocalDateTime now);
    List<ScreeningProjection> selectPublic(@Param("q") ScreeningQueryFilter query,
            @Param("now") LocalDateTime now, @Param("offset") long offset, @Param("limit") int limit);
    ScreeningProjection findPublic(@Param("id") long id, @Param("now") LocalDateTime now);
    long countAdmin(@Param("q") ScreeningQueryFilter query);
    List<ScreeningProjection> selectAdmin(@Param("q") ScreeningQueryFilter query,
            @Param("offset") long offset, @Param("limit") int limit);
    ScreeningProjection findAdmin(@Param("id") long id);
    List<ScreeningSeatProjection> selectSeats(@Param("screeningId") long screeningId);
}

