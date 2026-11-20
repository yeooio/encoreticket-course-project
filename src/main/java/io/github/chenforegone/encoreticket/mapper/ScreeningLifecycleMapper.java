package io.github.chenforegone.encoreticket.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 生命周期调度的有界候选查询，状态推进仍由场次事务服务负责。
 *
 * @author c1342
 */
@Mapper
public interface ScreeningLifecycleMapper {

    List<Candidate> selectEndedCandidates(@Param("now") LocalDateTime now,
            @Param("afterTime") LocalDateTime afterTime, @Param("afterId") Long afterId,
            @Param("limit") int limit);

    List<Candidate> selectOffSaleCandidates(@Param("now") LocalDateTime now,
            @Param("afterTime") LocalDateTime afterTime, @Param("afterId") Long afterId,
            @Param("limit") int limit);

    List<Candidate> selectOnSaleCandidates(@Param("now") LocalDateTime now,
            @Param("afterTime") LocalDateTime afterTime, @Param("afterId") Long afterId,
            @Param("limit") int limit);

    /**
     * 同时刻按主键稳定排序，避免未成功处理的记录挡住后续批次。
     *
     * @author c1342
     */
    record Candidate(Long id, LocalDateTime candidateTime) {
    }
}
