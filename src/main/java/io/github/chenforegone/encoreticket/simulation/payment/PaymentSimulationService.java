package io.github.chenforegone.encoreticket.simulation.payment;

import io.github.chenforegone.encoreticket.gateway.PaymentChannelEvent;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 测试环境支付只读查询，不改变支付及订单状态。
 * @author c1342
 */
@Service
@Profile("(local | test | loadtest) & !prod")
@ConditionalOnProperty(name = "encore.payment.simulation.enabled", havingValue = "true")
public class PaymentSimulationService {
    private final JdbcClient jdbc;

    public PaymentSimulationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public AttemptPage attempts(String paymentNo, int limit, String cursor) {
        if (paymentNo == null || paymentNo.isBlank() || paymentNo.length() > 64 || limit < 1 || limit > 100) {
            throw new io.github.chenforegone.encoreticket.common.exception.InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
        long after = -1;
        if (cursor != null) {
            try {
                if (!cursor.matches("[0-9]{1,19}")) throw new NumberFormatException();
                after = Long.parseLong(cursor);
            } catch (NumberFormatException exception) {
                throw new io.github.chenforegone.encoreticket.common.exception.InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        var payment = jdbc.sql("SELECT id, attempt_id FROM payment WHERE payment_no = :paymentNo LIMIT 1")
                .param("paymentNo", paymentNo).query((rs, row) ->
                        new PaymentIdentity(rs.getLong("id"), rs.getString("attempt_id"))).optional()
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PAYMENT_NOT_FOUND));
        var rows = jdbc.sql("""
                SELECT a.id AS sequence_id, a.remark AS attempt_id, a.create_time
                FROM payment_audit a
                WHERE a.payment_id = :paymentId AND a.operation = 'ATTEMPT_STARTED'
                  AND a.remark IS NOT NULL AND a.remark <> '' AND a.id > :after
                UNION ALL
                SELECT 0 AS sequence_id, p.attempt_id, NULL AS create_time
                FROM payment p WHERE p.id = :paymentId AND :after < 0
                  AND NOT EXISTS (SELECT 1 FROM payment_audit a WHERE a.payment_id = p.id
                    AND a.operation = 'ATTEMPT_STARTED' AND a.remark = p.attempt_id)
                ORDER BY sequence_id ASC LIMIT :fetch
                """).param("paymentId", payment.id()).param("after", after).param("fetch", limit + 1)
                .query((rs, row) -> {
                    var time = rs.getObject("create_time", java.time.LocalDateTime.class);
                    String attempt = rs.getString("attempt_id");
                    return new AttemptRow(rs.getLong("sequence_id"), new Attempt(paymentNo, attempt,
                            time == null ? null : time.toInstant(java.time.ZoneOffset.UTC),
                            payment.attemptId().equals(attempt)));
                }).list();
        var visible = rows.subList(0, Math.min(limit, rows.size()));
        return new AttemptPage(visible.stream().map(AttemptRow::attempt).toList(),
                rows.size() > limit ? Long.toString(visible.getLast().sequence()) : null);
    }

    public PaymentChannelEvent event(io.github.chenforegone.encoreticket.simulation.payment.PaymentSimulationEventRequest request) {
        return jdbc.sql("SELECT amount, currency FROM payment WHERE payment_no = :paymentNo LIMIT 1")
                .param("paymentNo", request.paymentNo()).query((rs, row) -> new PaymentChannelEvent(
                        "SIM", request.eventId(), request.paymentNo(), request.attemptId(), request.result().name(),
                        rs.getBigDecimal("amount"), rs.getString("currency"),
                        request.result() == io.github.chenforegone.encoreticket.simulation.payment.PaymentSimulationEventRequest.Result.SUCCESS))
                .optional().orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    /**
     * 支付当前尝试的只读标识。
     * @author c1342
     */
    private record PaymentIdentity(long id, String attemptId) { }

    /**
     * 审计游标与输出数据。
     * @author c1342
     */
    private record AttemptRow(long sequence, Attempt attempt) { }

    /**
     * 仅返回压测定位所需的已知尝试信息。
     * @author c1342
     */
    public record Attempt(String paymentNo, String attemptId, Instant createdAt, boolean current) { }

    /**
     * 有界尝试列表及后续查询游标。
     * @author c1342
     */
    public record AttemptPage(List<Attempt> items, String nextCursor) { }
}
