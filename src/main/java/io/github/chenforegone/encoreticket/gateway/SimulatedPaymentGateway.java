package io.github.chenforegone.encoreticket.gateway;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 默认保持 PENDING，由可信测试渠道显式提供结果，不自动成功。
 * @author c1342
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {
    @Override
    public void initiate(String paymentNo, String attemptId, BigDecimal amount, String currency) { }
}
