package io.github.chenforegone.encoreticket.model.vo;

import java.util.List;

/**
 * 本人订单的退款进度。
 * @author c1342
 */
public record OrderRefundsVO(String orderId, String orderStatus, List<RefundVO> items) { }
