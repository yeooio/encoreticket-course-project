ALTER TABLE sys_user
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0 COMMENT '登录态版本',
    ADD CONSTRAINT ck_sys_user_auth_version CHECK (auth_version >= 0);
