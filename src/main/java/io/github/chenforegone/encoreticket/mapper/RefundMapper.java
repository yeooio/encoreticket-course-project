package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.Refund;
import org.apache.ibatis.annotations.Mapper;

/**
 * 退款数据访问接口。
 *
 * @author c1342
 */
@Mapper
public interface RefundMapper extends BaseMapper<Refund> {
}
