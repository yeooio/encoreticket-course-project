package io.github.chenforegone.encoreticket.mapper;

import io.github.chenforegone.encoreticket.model.query.TicketProjection;
import io.github.chenforegone.encoreticket.model.query.TicketQueryFilter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 票券关联查询数据访问接口。
 *
 * @author c1342
 */
@Mapper
public interface TicketMapper {
    long count(@Param("filter") TicketQueryFilter filter);

    List<TicketProjection> selectPage(@Param("filter") TicketQueryFilter filter);

    TicketProjection selectOwned(@Param("ticketId") long ticketId, @Param("userId") long userId);

    boolean hasInvalidOwned(@Param("filter") TicketQueryFilter filter);
}
