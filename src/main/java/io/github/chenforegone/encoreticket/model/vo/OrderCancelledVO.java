package io.github.chenforegone.encoreticket.model.vo;

/**
 * 当前订单取消结果，不作为历史快照重放。
 * @author c1342
 */
public record OrderCancelledVO(String orderId,String status) { }
