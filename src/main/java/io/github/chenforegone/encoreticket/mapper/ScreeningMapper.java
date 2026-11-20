package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.Screening;
import io.github.chenforegone.encoreticket.model.query.ScreeningDetailProjection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 场次数据访问接口。
 *
 * @author c1342
 */
@Mapper
public interface ScreeningMapper extends BaseMapper<Screening> {

    @Select("SELECT id, price, movie_id, hall_id, start_time, end_time, sale_start_time, sale_end_time, admission_open_time, duration_minutes_snapshot, turnaround_minutes_snapshot, status, version, create_time, update_time "
            + "FROM screening WHERE hall_id = #{hallId} ORDER BY id FOR UPDATE")
    List<Screening> lockHallSchedule(@Param("hallId") Long hallId);

    @Select("SELECT COUNT(*) FROM movie WHERE id = #{movieId}")
    int countMovie(@Param("movieId") Long movieId);

    @Select("SELECT COUNT(*) FROM hall WHERE id = #{hallId} AND status = 0")
    int countEnabledHall(@Param("hallId") Long hallId);

    @Select("SELECT id FROM hall WHERE id = #{hallId} FOR UPDATE")
    Long lockHall(@Param("hallId") Long hallId);

    @Update("UPDATE screening SET movie_id = #{screening.movieId}, hall_id = #{screening.hallId}, price = #{screening.price}, start_time = #{screening.startTime}, end_time = #{screening.endTime}, sale_start_time = #{screening.saleStartTime}, sale_end_time = #{screening.saleEndTime}, admission_open_time = #{screening.admissionOpenTime}, version = version + 1 WHERE id = #{screening.id} AND version = #{version} AND status = 0")
    int updateOptimistic(@Param("screening") Screening screening, @Param("version") Integer version);

    @Select("SELECT s.id, s.movie_id, m.title AS movie_title, s.hall_id, h.hall_name, s.price, s.start_time, s.end_time, "
            + "s.sale_start_time, s.sale_end_time, s.admission_open_time, s.status, s.version "
            + "FROM screening s JOIN movie m ON m.id = s.movie_id JOIN hall h ON h.id = s.hall_id WHERE s.id = #{id}")
    ScreeningDetailProjection selectAdminDetail(@Param("id") Long id);
    List<Screening> selectPublicByMovieId(@Param("movieId") Long movieId);

    long countOverlap(@Param("hallId") long hallId, @Param("excludedId") Long excludedId,
            @Param("start") LocalDateTime start, @Param("occupiedEnd") LocalDateTime occupiedEnd);
    long countOrders(@Param("id") long id);
    long countActiveOrders(@Param("id") long id);
    long countOccupiedSeats(@Param("id") long id);
    long countAvailableSeats(@Param("id") long id);
    long countHallReferences(@Param("hallId") long hallId);
    long countSeatReferences(@Param("seatId") long seatId);
    int replaceWaitSale(@Param("value") Screening value, @Param("expectedVersion") int expectedVersion,
            @Param("now") LocalDateTime now);
    int changeStatus(@Param("id") long id, @Param("version") int version,
            @Param("from") int from, @Param("to") int to,
            @Param("saleStart") LocalDateTime saleStart, @Param("now") LocalDateTime now);
    int claimForClose(@Param("id") long id, @Param("version") int version, @Param("now") LocalDateTime now);
    int finishClose(@Param("id") long id, @Param("version") int version, @Param("now") LocalDateTime now);
}
