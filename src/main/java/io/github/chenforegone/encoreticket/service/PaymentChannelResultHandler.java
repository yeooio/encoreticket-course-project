package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.gateway.PaymentChannelEvent;
import io.github.chenforegone.encoreticket.model.vo.PaymentVO;

/**
 * 可信支付渠道结果入口，不向普通客户端开放。
 * @author c1342
 */
public interface PaymentChannelResultHandler {
    PaymentVO handleChannelEvent(PaymentChannelEvent event);
}
