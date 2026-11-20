-- 停写并备份后升级；先拒绝关联不一致存量，不修复、不删除资金历史。
CREATE TEMPORARY TABLE refund_v27_guard (
    issue_count BIGINT NOT NULL,
    CONSTRAINT ck_refund_v27_preflight CHECK (issue_count=0)
);
INSERT INTO refund_v27_guard(issue_count)
SELECT COUNT(*) FROM refund r
LEFT JOIN payment p ON p.id=r.payment_id
LEFT JOIN orders o ON o.id=r.order_id
WHERE p.id IS NULL OR o.id IS NULL OR p.order_id<>r.order_id OR p.status<>1
   OR r.amount<>p.amount OR r.amount<>o.total_amount OR r.amount<=0
   OR (r.status IN (0,2) AND o.status<>4) OR (r.status=1 AND o.status<>5);
DROP TEMPORARY TABLE refund_v27_guard;

-- 存量退款来源不可可靠推断，保守标记 LEGACY；PENDING 只查询，不自动重新发起。
ALTER TABLE refund
    ADD COLUMN source VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN attempt_no INT NOT NULL DEFAULT 1,
    ADD COLUMN next_run_at DATETIME(6) NULL,
    ADD COLUMN lease_token VARCHAR(64) NULL,
    ADD COLUMN lease_until DATETIME(6) NULL,
    ADD CONSTRAINT ck_refund_source CHECK (source IN ('USER','LATE_PAYMENT','LEGACY')),
    ADD CONSTRAINT ck_refund_attempt_no CHECK (attempt_no > 0),
    ADD KEY idx_refund_pending_run (status,next_run_at,id);

CREATE TABLE refund_attempt (
    refund_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    submitted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (refund_id,attempt_no),
    CONSTRAINT ck_refund_attempt_status CHECK (status IN ('PENDING','SUCCESS','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refund_audit (
    id BIGINT PRIMARY KEY,
    refund_id BIGINT NOT NULL,
    operation VARCHAR(64) NOT NULL,
    operator_id BIGINT NULL,
    create_time DATETIME(6) NOT NULL,
    KEY idx_refund_audit_refund (refund_id,create_time,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 此表代表模拟渠道自身的不可变资金事实；与业务联动分事务提交。
CREATE TABLE refund_simulated_result (
    refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no INT NOT NULL,
    payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    result VARCHAR(16) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    successful_refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN result='SUCCESS' THEN refund_no ELSE NULL END) STORED,
    PRIMARY KEY (refund_no,attempt_no),
    UNIQUE KEY uk_refund_simulated_success (successful_refund_no),
    CONSTRAINT ck_refund_simulated_result CHECK (result IN ('SUCCESS','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO refund_attempt(refund_id,attempt_no,status,submitted,create_time,update_time)
SELECT id,1,CASE status WHEN 1 THEN 'SUCCESS' WHEN 2 THEN 'FAILED' ELSE 'PENDING' END,TRUE,create_time,update_time FROM refund;
INSERT INTO refund_audit(id,refund_id,operation,operator_id,create_time)
SELECT id,id,'LEGACY_IMPORTED',NULL,update_time FROM refund;
UPDATE refund SET next_run_at=UTC_TIMESTAMP(6) WHERE status=0;
