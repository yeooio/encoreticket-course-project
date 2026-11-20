-- 恢复场次快照扩展；V3、V20、V21、V22 保持不可变。
-- 不从现有放映时间猜测片长，不重写价格、排片或已保存的入场时间。
-- 已有旧扩展列时保留原值；缺失列保持可补录，V26 再做收紧预检。
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'movie' AND column_name = 'duration_minutes'),
    'DO 0', 'ALTER TABLE movie ADD COLUMN duration_minutes INT NULL COMMENT ''影片播放时长（分钟），待补录'' AFTER release_date');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'hall' AND column_name = 'schedule_version'),
    'DO 0', 'ALTER TABLE hall ADD COLUMN schedule_version BIGINT NOT NULL DEFAULT 0 COMMENT ''同影厅排片乐观锁版本'' AFTER status');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'screening' AND column_name = 'duration_minutes_snapshot'),
    'DO 0', 'ALTER TABLE screening ADD COLUMN duration_minutes_snapshot INT NULL COMMENT ''排片时片长快照（分钟），待补录'' AFTER end_time');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'screening' AND column_name = 'turnaround_minutes_snapshot'),
    'DO 0', 'ALTER TABLE screening ADD COLUMN turnaround_minutes_snapshot INT NOT NULL DEFAULT 15 COMMENT ''场间周转时间快照（分钟）'' AFTER duration_minutes_snapshot');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'movie' AND constraint_name = 'ck_movie_duration_minutes'),
    'DO 0', 'ALTER TABLE movie ADD CONSTRAINT ck_movie_duration_minutes CHECK (duration_minutes > 0)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'hall' AND constraint_name = 'ck_hall_schedule_version'),
    'DO 0', 'ALTER TABLE hall ADD CONSTRAINT ck_hall_schedule_version CHECK (schedule_version >= 0)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'screening' AND constraint_name = 'ck_screening_duration_minutes'),
    'DO 0', 'ALTER TABLE screening ADD CONSTRAINT ck_screening_duration_minutes CHECK (duration_minutes_snapshot > 0)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'screening' AND constraint_name = 'ck_screening_turnaround_minutes'),
    'DO 0', 'ALTER TABLE screening ADD CONSTRAINT ck_screening_turnaround_minutes CHECK (turnaround_minutes_snapshot >= 0)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;

-- 新索引不删除已有索引，兼容已经有旧场次扩展的结构。
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'screening' AND index_name = 'idx_screening_hall_start_id'),
    'DO 0', 'ALTER TABLE screening ADD KEY idx_screening_hall_start_id (hall_id, start_time, id)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'screening' AND index_name = 'idx_screening_movie_status_start_id'),
    'DO 0', 'ALTER TABLE screening ADD KEY idx_screening_movie_status_start_id (movie_id, status, start_time, id)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orders' AND index_name = 'idx_orders_screening_status_expiry_id'),
    'DO 0', 'ALTER TABLE orders ADD KEY idx_orders_screening_status_expiry_id (screening_id, status, expires_at, id)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'orders' AND index_name = 'idx_orders_status_expiry_id'),
    'DO 0', 'ALTER TABLE orders ADD KEY idx_orders_status_expiry_id (status, expires_at, id)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
-- api_idempotency 继续使用 V21 的摘要和响应格式，支付与核销共用。

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'screening' AND index_name = 'idx_screening_status_start_id'), 'DO 0', 'ALTER TABLE screening ADD KEY idx_screening_status_start_id (status, start_time, id)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'screening' AND index_name = 'idx_screening_status_sale_start_id'), 'DO 0', 'ALTER TABLE screening ADD KEY idx_screening_status_sale_start_id (status, sale_start_time, id)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'screening' AND index_name = 'idx_screening_status_sale_end_id'), 'DO 0', 'ALTER TABLE screening ADD KEY idx_screening_status_sale_end_id (status, sale_end_time, id)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'screening' AND index_name = 'idx_screening_status_end_id'), 'DO 0', 'ALTER TABLE screening ADD KEY idx_screening_status_end_id (status, end_time, id)');
PREPARE expansion FROM @ddl;
EXECUTE expansion;
DEALLOCATE PREPARE expansion;
