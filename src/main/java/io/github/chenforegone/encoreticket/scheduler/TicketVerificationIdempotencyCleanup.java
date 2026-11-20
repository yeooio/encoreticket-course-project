package io.github.chenforegone.encoreticket.scheduler;

import io.github.chenforegone.encoreticket.service.transaction.TicketVerificationTransaction;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 每分钟有界清理核销操作到期幂等快照，不删除核销审计。
 *
 * @author c1342
 */
@Configuration
@EnableScheduling
public class TicketVerificationIdempotencyCleanup {
    private final TicketVerificationTransaction transaction;
    public TicketVerificationIdempotencyCleanup(TicketVerificationTransaction transaction) { this.transaction=transaction; }

    @Scheduled(fixedDelayString="${encore.ticket-verification.cleanup-delay-ms:60000}",initialDelay=60000)
    public void cleanup() {
        try { transaction.cleanup(); }
        catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(TicketVerificationIdempotencyCleanup.class)
                    .warn("核销幂等清理失败 exceptionType={}",e.getClass().getSimpleName());
        }
    }
}
