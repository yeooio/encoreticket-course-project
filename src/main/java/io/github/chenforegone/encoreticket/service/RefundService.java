package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.model.vo.RefundVO;
import io.github.chenforegone.encoreticket.model.vo.OrderRefundsVO;

/**
 * 退款申请、进度与异常处理边界。
 * @author c1342
 */
public interface RefundService {
    RefundVO apply(Long orderId, Long userId, String key);
    OrderRefundsVO byOrder(Long orderId, Long userId);
    RefundVO adminProcess(Long refundId, Long operator, String key, String action);
    io.github.chenforegone.encoreticket.model.vo.RefundDetailVO adminDetail(Long refundId);
    io.github.chenforegone.encoreticket.common.response.PageResponse<RefundVO> adminPage(int page, int size, String status,
            String source, Long orderId, java.time.Instant from, java.time.Instant to);
}
