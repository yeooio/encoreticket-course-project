package io.github.chenforegone.encoreticket.scheduler;

import io.github.chenforegone.encoreticket.common.time.Clock;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 有界清理支付到期响应快照，保留业务记录、通知与审计。
 *
 * @author c1342
 */
@Configuration
@EnableScheduling
public class PaymentIdempotencyCleanup {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentIdempotencyCleanup.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PaymentIdempotencyCleanup(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${encore.payment.cleanup-delay-ms:60000}", initialDelay = 60000)
    public void cleanup() {
        try {
            jdbc.update("DELETE FROM api_idempotency WHERE expires_at <= ? "
                            + "AND operation IN ('payment-create:v1','payment-admin-retry:v1') "
                            + "ORDER BY expires_at LIMIT 500",
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        } catch (RuntimeException exception) {
            LOGGER.warn("支付幂等清理失败 exceptionType={}", exception.getClass().getSimpleName());
        }
    }
}
