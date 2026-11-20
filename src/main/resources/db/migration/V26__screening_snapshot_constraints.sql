-- Keep application writes stopped from preflight through migration.
-- Reject known invalid data before the first business-table DDL; no data repair.
CREATE TEMPORARY TABLE screening_v26_guard (
    issue_count BIGINT NOT NULL,
    CONSTRAINT ck_screening_v26_preflight CHECK (issue_count = 0)
);
INSERT INTO screening_v26_guard (issue_count)
SELECT COUNT(*) FROM (
-- Run against a V25 schema using a read-only connection. This reports identifiers only.
SELECT 'MOVIE_DURATION' AS issue_code, id AS entity_id, NULL AS related_id
FROM movie WHERE duration_minutes IS NULL OR duration_minutes <= 0
UNION ALL
SELECT 'SCREENING_DURATION', id, NULL FROM screening
WHERE duration_minutes_snapshot IS NULL OR duration_minutes_snapshot <= 0
UNION ALL
SELECT 'SCREENING_PRICE', id, NULL FROM screening WHERE price <= 0
UNION ALL
SELECT 'SALE_CUTOFF', id, NULL FROM screening
WHERE TIMESTAMPDIFF(SECOND, sale_end_time, start_time) <> 900
UNION ALL
SELECT 'NON_MINUTE_DURATION', id, NULL FROM screening
WHERE MOD(TIMESTAMPDIFF(SECOND, start_time, end_time), 60) <> 0
UNION ALL
SELECT 'MISSING_HALL', s.id, s.hall_id FROM screening s
LEFT JOIN hall h ON h.id = s.hall_id WHERE h.id IS NULL
UNION ALL
SELECT 'BEFORE_RELEASE', s.id, s.movie_id FROM screening s
JOIN movie m ON m.id = s.movie_id
WHERE s.status <> 3 AND DATE(s.start_time) < m.release_date
UNION ALL
SELECT 'END_DURATION', id, NULL FROM screening
WHERE TIMESTAMPDIFF(SECOND, start_time, end_time) <> duration_minutes_snapshot * 60
UNION ALL
SELECT 'MISSING_MOVIE', s.id, s.movie_id FROM screening s
LEFT JOIN movie m ON m.id = s.movie_id WHERE m.id IS NULL
UNION ALL
SELECT 'SCREENING_STATUS', id, NULL FROM screening WHERE status NOT IN (0, 1, 2, 3, 4)
UNION ALL
SELECT 'MOVIE_STATUS', id, NULL FROM movie WHERE status NOT IN (0, 1)
UNION ALL
SELECT 'HALL_STATUS', id, NULL FROM hall WHERE status NOT IN (0, 1)
UNION ALL
-- CLOSED (3) alone releases its interval; OFF_SALE (2) and ENDED (4) retain it.
-- Compare seconds instead of adding to DATETIME, avoiding overflow near year 9999.
SELECT 'HALL_OVERLAP', a.id, b.id FROM screening a
JOIN screening b ON a.hall_id = b.hall_id AND a.id < b.id
WHERE a.status <> 3 AND b.status <> 3
  AND TIMESTAMPDIFF(SECOND, b.start_time, a.end_time) + a.turnaround_minutes_snapshot * 60 > 0
  AND TIMESTAMPDIFF(SECOND, a.start_time, b.end_time) + b.turnaround_minutes_snapshot * 60 > 0
) AS blocking_issues;
DROP TEMPORARY TABLE screening_v26_guard;

-- Apply constraints only after the preflight guard succeeds.
-- Never fabricate durations or rewrite existing prices or business times.
ALTER TABLE movie
    MODIFY COLUMN duration_minutes INT NOT NULL COMMENT '影片播放时长（分钟）';

ALTER TABLE screening
    MODIFY COLUMN duration_minutes_snapshot INT NOT NULL COMMENT '排片时片长快照（分钟）',
    DROP CHECK ck_screening_price,
    ADD CONSTRAINT ck_screening_price CHECK (price > 0);

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'screening' AND constraint_name = 'ck_screening_end_duration'),
    'DO 0', 'ALTER TABLE screening ADD CONSTRAINT ck_screening_end_duration CHECK (TIMESTAMPDIFF(SECOND, start_time, end_time) = duration_minutes_snapshot * 60)');
PREPARE constraints_stmt FROM @ddl;
EXECUTE constraints_stmt;
DEALLOCATE PREPARE constraints_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'screening' AND constraint_name = 'ck_screening_sale_cutoff'),
    'DO 0', 'ALTER TABLE screening ADD CONSTRAINT ck_screening_sale_cutoff CHECK (TIMESTAMPDIFF(SECOND, sale_end_time, start_time) = 900)');
PREPARE constraints_stmt FROM @ddl;
EXECUTE constraints_stmt;
DEALLOCATE PREPARE constraints_stmt;

ALTER TABLE orders
    MODIFY COLUMN expires_at DATETIME NOT NULL
        COMMENT '实际支付截止（UTC）：下单后15分钟与场次售票截止取早；不回写历史订单';
