package io.github.chenforegone.encoreticket.gateway;

import io.github.chenforegone.encoreticket.common.time.Clock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 持久化的模拟渠道；结果写入不与本地退款联动共用事务。
 * @author c1342
 */
@Component
public class SimulatedRefundGateway implements RefundGateway {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public SimulatedRefundGateway(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    @Override public Result submit(String refundNo,int attempt,String paymentNo,BigDecimal amount) {
        // 所有轮次共用退款业务号；已成功资金事实禁止再次退款。
        var successful=jdbc.query("SELECT payment_no,amount,result FROM refund_simulated_result WHERE refund_no=? AND result='SUCCESS'",
                (rs,n)->new Fact(rs.getString(1),rs.getBigDecimal(2),rs.getString(3)),refundNo);
        if (!successful.isEmpty()) return validate(successful.getFirst(),paymentNo,amount);
        jdbc.update("INSERT INTO refund_simulated_result(refund_no,attempt_no,payment_no,amount,result,create_time) VALUES(?,?,?,?,'SUCCESS',?) ON DUPLICATE KEY UPDATE refund_no=refund_no",
                refundNo,attempt,paymentNo,amount,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        return query(refundNo,attempt,paymentNo,amount);
    }
    @Override public Result query(String refundNo,int attempt,String paymentNo,BigDecimal amount) {
        var rows=jdbc.query("SELECT payment_no,amount,result FROM refund_simulated_result WHERE refund_no=? AND (attempt_no=? OR result='SUCCESS') ORDER BY result DESC LIMIT 1",
                (rs,n)->new Fact(rs.getString(1),rs.getBigDecimal(2),rs.getString(3)),refundNo,attempt);
        return rows.isEmpty()?Result.NOT_FOUND:validate(rows.getFirst(),paymentNo,amount);
    }
    private Result validate(Fact fact,String paymentNo,BigDecimal amount) {
        if (!fact.paymentNo.equals(paymentNo)||fact.amount.compareTo(amount)!=0) throw new IllegalStateException("模拟退款资金摘要冲突");
        return Result.valueOf(fact.result);
    }
    /**
     * 模拟渠道资金摘要。
     * @author c1342
     */
    private record Fact(String paymentNo,BigDecimal amount,String result) { }
}
