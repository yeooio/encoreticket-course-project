package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.ApplicationException;

/**
 * 过期事实提交后返回的安全业务结果。
 * @author c1342
 */
public final class OrderExpiredException extends ApplicationException {
    public OrderExpiredException() { super(ErrorCode.RESOURCE_CONFLICT,"订单已过期"); }
}
