package io.github.chenforegone.encoreticket.common.constant;

/**
 * 当前请求内的异常上下文键。
 *
 * @author c1342
 */
public final class ErrorContextConstant {

    public static final String ORIGINAL_EXCEPTION_ATTRIBUTE =
            ErrorContextConstant.class.getName() + ".originalException";

    private ErrorContextConstant() {
    }
}
