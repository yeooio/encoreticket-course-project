package io.github.chenforegone.encoreticket.gateway;

import java.math.BigDecimal;

/**
 * 本地渠道边界；真实外部扣款接入时必须使用事务消息或对账协议。
 * @author c1342
 */
public interface PaymentGateway {
    void initiate(String paymentNo, String attemptId, BigDecimal amount, String currency);
}
