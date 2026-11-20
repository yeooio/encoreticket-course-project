package io.github.chenforegone.encoreticket.service.rules;

import io.github.chenforegone.encoreticket.mapper.ScreeningMapper;
import io.github.chenforegone.encoreticket.model.entity.Screening;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 影厅排片并发保护，按影厅 ID 升序获取行锁。
 *
 * @author c1342
 */
@Component
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class HallScheduleGuard {

    private final ScreeningMapper screeningMapper;

    public HallScheduleGuard(ScreeningMapper screeningMapper) {
        this.screeningMapper = screeningMapper;
    }

    /**
     * Hall-only 主键行锁，与 HallMapper.lockRow 竞争同一 InnoDB 记录。
     * 不锁场次、订单，不在持锁期间调用过期清理。
     */
    public List<Long> lockHallRows(Long... hallIds) {
        List<Long> orderedIds = java.util.Arrays.stream(hallIds)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        for (Long id : orderedIds) {
            if (screeningMapper.lockHall(id) == null) {
                throw new io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException();
            }
        }
        return orderedIds;
    }

    public List<Screening> lockHalls(Long... hallIds) {
        return lockHallRows(hallIds).stream()
                .flatMap(id -> screeningMapper.lockHallSchedule(id).stream())
                .toList();
    }
}
