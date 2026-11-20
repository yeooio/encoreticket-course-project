package io.github.chenforegone.encoreticket.common.exception;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.ApplicationException;

/**
 * 核销安全错误，禁止附带请求原文或 SQL。
 *
 * @author c1342
 */
public class TicketVerificationException extends ApplicationException {
    public TicketVerificationException(ErrorCode code) { super(code); }
    public TicketVerificationException(ErrorCode code,Throwable cause) { super(code,cause); }
}
