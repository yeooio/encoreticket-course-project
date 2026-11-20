package io.github.chenforegone.encoreticket.scheduler;

import io.github.chenforegone.encoreticket.service.impl.RefundServiceImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 有界扫描持久化退款意图和独立清理退款快照。
 * @author c1342
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="encore.refund.scheduler-enabled",havingValue="true",matchIfMissing=true)
public class RefundScheduler {
    private final RefundServiceImpl refunds;
    public RefundScheduler(RefundServiceImpl refunds) { this.refunds=refunds; }
    @Scheduled(fixedDelayString="${encore.refund.processing-delay-ms:1000}")
    public void process() { refunds.runPending(100); }
    @Scheduled(fixedDelayString="${encore.refund.cleanup-delay-ms:60000}")
    public void cleanup() { refunds.cleanup(); }
}
