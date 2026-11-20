-- 核销成功快照，与票券状态和审计在同一事务内提交。
-- ascii_bin 保留操作名、幂等键的大小写；operator_id 为精确数值标识。
CREATE TABLE api_idempotency (
    operator_id BIGINT NOT NULL,
    operation VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json JSON NULL,
    expires_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_api_idempotency_scope (operator_id, operation, idempotency_key),
    KEY idx_api_idempotency_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API成功幂等快照';
