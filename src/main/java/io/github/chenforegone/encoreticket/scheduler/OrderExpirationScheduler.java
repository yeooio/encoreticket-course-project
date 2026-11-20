package io.github.chenforegone.encoreticket.scheduler;

import io.github.chenforegone.encoreticket.service.impl.OrderExpirationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 触发有界订单到期扫描，查询兜底复用相同的订单事务处理器。
 *
 * @author c1342
 */
@Component
@ConditionalOnProperty(prefix = "encore.order.expiration-scheduler", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OrderExpirationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(OrderExpirationScheduler.class);
    private final OrderExpirationService service;

    public OrderExpirationScheduler(OrderExpirationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${encore.order.expiration-scheduler.delay-ms:60000}")
    public void expireDueOrders() {
        try {
            service.expireDueOrders();
        } catch (RuntimeException exception) {
            LOG.warn("Order expiration scan failed: failureType={}", exception.getClass().getName());
        }
    }
}
