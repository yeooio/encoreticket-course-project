-- 电影排期与乐观锁；保留已有扩展列和值，不改 master 已应用的支付 V3。
-- 先检查不可自动修复的数据，避免在业务表 DDL 后才发现空上映日期或重复电影。
CREATE TEMPORARY TABLE movie_v23_guard (issue_count BIGINT NOT NULL CHECK (issue_count = 0));
INSERT INTO movie_v23_guard
SELECT COUNT(*) FROM (
    SELECT id FROM movie WHERE release_date IS NULL
    UNION ALL
    SELECT MIN(id) FROM movie GROUP BY title, release_date HAVING COUNT(*) > 1
) invalid_movies;
DROP TEMPORARY TABLE movie_v23_guard;

SET @movie_has_publish = EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'movie' AND column_name = 'publish_at');
SET @ddl = IF(@movie_has_publish, 'DO 0',
    'ALTER TABLE movie ADD COLUMN publish_at DATETIME NULL COMMENT ''系统自动发行时间，按 UTC 存储'' AFTER release_date');
PREPARE movie_upgrade FROM @ddl;
EXECUTE movie_upgrade;
DEALLOCATE PREPARE movie_upgrade;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'movie' AND column_name = 'version'), 'DO 0',
    'ALTER TABLE movie ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本'' AFTER status');
PREPARE movie_upgrade FROM @ddl;
EXECUTE movie_upgrade;
DEALLOCATE PREPARE movie_upgrade;
ALTER TABLE movie MODIFY COLUMN release_date DATE NOT NULL COMMENT '上映日期', ALTER COLUMN status SET DEFAULT 1;
-- 仅在新增发行列时回填；保留已有自动发行计划。
UPDATE movie SET publish_at = CASE WHEN status = 0 THEN create_time ELSE NULL END
WHERE NOT @movie_has_publish;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'movie' AND index_name = 'uk_movie_title_release_date'), 'DO 0',
    'ALTER TABLE movie ADD UNIQUE KEY uk_movie_title_release_date (title, release_date)');
PREPARE movie_upgrade FROM @ddl;
EXECUTE movie_upgrade;
DEALLOCATE PREPARE movie_upgrade;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'movie' AND index_name = 'idx_movie_status_publish_at_id'), 'DO 0',
    'ALTER TABLE movie ADD KEY idx_movie_status_publish_at_id (status, publish_at, id)');
PREPARE movie_upgrade FROM @ddl;
EXECUTE movie_upgrade;
DEALLOCATE PREPARE movie_upgrade;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'movie' AND index_name = 'idx_movie_status_release_date_id'), 'DO 0',
    'ALTER TABLE movie ADD KEY idx_movie_status_release_date_id (status, release_date, id)');
PREPARE movie_upgrade FROM @ddl;
EXECUTE movie_upgrade;
DEALLOCATE PREPARE movie_upgrade;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'movie' AND index_name = 'idx_movie_title'), 'DO 0',
    'ALTER TABLE movie ADD KEY idx_movie_title (title)');
PREPARE movie_upgrade FROM @ddl;
EXECUTE movie_upgrade;
DEALLOCATE PREPARE movie_upgrade;
