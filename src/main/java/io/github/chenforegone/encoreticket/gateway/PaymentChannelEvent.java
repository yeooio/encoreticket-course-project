package io.github.chenforegone.encoreticket.gateway;

import java.math.BigDecimal;

/**
 * 已通过可信渠道认证的结果；attemptId 必须来自发起渠道请求时保存的标识。
 * @author c1342
 */
public record PaymentChannelEvent(String provider, String eventId, String paymentNo,
        String attemptId, String eventType, BigDecimal amount, String currency, boolean successful) { }
