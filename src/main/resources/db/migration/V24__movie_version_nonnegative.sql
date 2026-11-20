-- 保留已存在的约束；负版本必须调查，不能自动清零。
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'movie' AND constraint_name = 'ck_movie_version'),
    'DO 0', 'ALTER TABLE movie ADD CONSTRAINT ck_movie_version CHECK (version >= 0)');
PREPARE movie_constraint FROM @ddl;
EXECUTE movie_constraint;
DEALLOCATE PREPARE movie_constraint;
