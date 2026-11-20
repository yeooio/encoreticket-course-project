package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.PaymentAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 支付审计数据访问接口。
 *
 * @author c1342
 */
@Mapper
public interface PaymentAuditMapper extends BaseMapper<PaymentAudit> {
}
