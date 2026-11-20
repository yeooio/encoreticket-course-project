package io.github.chenforegone.encoreticket.mapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 支付幂等占位和结果与业务写入共用事务，过期键在持锁后复用。
 * @author c1342
 */
@Repository
public class PaymentIdempotencyMapper {
    /**
     * 已提交请求快照。
     * @author c1342
     */
    public record Snapshot(String digest, String responseJson, Instant expiresAt) { }
    private final JdbcTemplate jdbc;
    public PaymentIdempotencyMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Snapshot find(long operatorId, String operation, String key, boolean lock) {
        return jdbc.query("SELECT request_digest,response_json,expires_at FROM api_idempotency WHERE operator_id=? AND operation=? AND idempotency_key=?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Snapshot(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                operatorId, operation, key).stream().findFirst().orElse(null);
    }
    public void reserve(long operatorId, String operation, String key, String digest, Instant now) {
        jdbc.update("INSERT INTO api_idempotency(operator_id,operation,idempotency_key,request_digest,response_json,expires_at) VALUES(?,?,?,?,NULL,?) ON DUPLICATE KEY UPDATE operator_id=operator_id",
                operatorId, operation, key, digest, utc(now.plusSeconds(86400)));
    }
    public void delete(long operatorId, String operation, String key) {
        jdbc.update("DELETE FROM api_idempotency WHERE operator_id=? AND operation=? AND idempotency_key=?", operatorId, operation, key);
    }
    public int save(long operatorId, String operation, String key, String responseJson, Instant now) {
        return jdbc.update("UPDATE api_idempotency SET response_json=?,expires_at=? WHERE operator_id=? AND operation=? AND idempotency_key=? AND response_json IS NULL",
                responseJson, utc(now.plusSeconds(86400)), operatorId, operation, key);
    }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
