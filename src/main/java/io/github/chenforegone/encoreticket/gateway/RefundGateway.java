package io.github.chenforegone.encoreticket.gateway;

import java.math.BigDecimal;

/**
 * 原路渠道边界；未知时只能核查同一资金标识。
 * @author c1342
 */
public interface RefundGateway {
    /**
     * 可信渠道结果；NOT_FOUND 仅表示可安全以同一标识提交。
     * @author c1342
     */
    enum Result { SUCCESS, FAILED, UNKNOWN, NOT_FOUND }
    Result submit(String refundNo, int attempt, String paymentNo, BigDecimal amount);
    Result query(String refundNo, int attempt, String paymentNo, BigDecimal amount);
}
