package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;

/**
 * 已在认证依赖边界识别的不可用故障。
 *
 * @author c1342
 */
public class DependencyUnavailableException extends ApplicationException {
    public DependencyUnavailableException(Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE, cause);
    }
}
