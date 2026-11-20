package io.github.chenforegone.encoreticket.service;

/**
 * 本人订单公开业务入口。
 * @author c1342
 */
public interface OrderService {
    io.github.chenforegone.encoreticket.common.response.PageResponse<io.github.chenforegone.encoreticket.model.vo.OrderSummaryVO> list(Long userId,
            io.github.chenforegone.encoreticket.model.dto.OrderQueryRequest query);
    io.github.chenforegone.encoreticket.model.vo.OrderDetailVO detail(Long userId,String orderId);
    io.github.chenforegone.encoreticket.model.vo.OrderCancelledVO cancel(Long userId,String orderId);
    io.github.chenforegone.encoreticket.model.vo.OrderDetailVO create(Long userId, String key,
            io.github.chenforegone.encoreticket.model.dto.CreateOrderRequest request);
}
