package io.github.chenforegone.encoreticket.model.dto;

/**
 * 本人订单分页及精确筛选输入。
 * @author c1342
 */
public record OrderQueryRequest(Integer page,Integer pageSize,String status,String screeningId,String orderNo) { }
