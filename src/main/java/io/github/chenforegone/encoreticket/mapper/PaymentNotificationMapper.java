package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chenforegone.encoreticket.model.entity.PaymentNotification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 支付通知数据访问接口。
 *
 * @author c1342
 */
@Mapper
public interface PaymentNotificationMapper extends BaseMapper<PaymentNotification> {
}
