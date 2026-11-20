-- 场次入场配置与乐观锁支持。
-- 先预检历史数据；发现相邻场次或时间约束异常时让迁移失败，不静默改排。

SET @screening_has_version = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'screening' AND column_name = 'version'
);

SET @screening_has_admission_open_time = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'screening' AND column_name = 'admission_open_time'
);

SET @screening_precheck_invalid = (
    SELECT COUNT(*) FROM (
        SELECT s.id
        FROM screening s
        WHERE s.end_time <= s.start_time
           OR s.sale_end_time >= s.start_time
           OR s.sale_start_time >= s.sale_end_time
        UNION ALL
        SELECT earlier.id
        FROM screening earlier
        JOIN screening later ON later.hall_id = earlier.hall_id
            AND later.start_time >= earlier.start_time
            AND later.id <> earlier.id
        WHERE earlier.end_time + INTERVAL 15 MINUTE > later.start_time
    ) invalid_rows
);

SET @screening_precheck_sql = IF(@screening_precheck_invalid > 0,
    'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''screening historical schedule precheck failed''',
    'SET @screening_precheck_ok = 1');
PREPARE screening_precheck_stmt FROM @screening_precheck_sql;
EXECUTE screening_precheck_stmt;
DEALLOCATE PREPARE screening_precheck_stmt;

SET @screening_add_admission_sql = IF(@screening_has_admission_open_time = 0,
    'ALTER TABLE screening ADD COLUMN admission_open_time DATETIME NULL AFTER sale_end_time',
    'SET @screening_admission_exists = 1');
PREPARE screening_add_admission_stmt FROM @screening_add_admission_sql;
EXECUTE screening_add_admission_stmt;
DEALLOCATE PREPARE screening_add_admission_stmt;

UPDATE screening
SET admission_open_time = DATE_SUB(start_time, INTERVAL 15 MINUTE)
WHERE admission_open_time IS NULL;

ALTER TABLE screening
    MODIFY COLUMN admission_open_time DATETIME NOT NULL COMMENT '允许入场时间，影院 Asia/Shanghai 业务时间';

SET @screening_add_version_sql = IF(@screening_has_version = 0,
    'ALTER TABLE screening ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER status',
    'SET @screening_version_exists = 1');
PREPARE screening_add_version_stmt FROM @screening_add_version_sql;
EXECUTE screening_add_version_stmt;
DEALLOCATE PREPARE screening_add_version_stmt;

ALTER TABLE screening
    ADD CONSTRAINT ck_screening_admission_before_start CHECK (admission_open_time <= start_time),
    ADD CONSTRAINT ck_screening_version CHECK (version >= 0);
