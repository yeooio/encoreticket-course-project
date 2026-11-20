-- 仅新增维护版本；不改变排片版本、物理状态或历史场次席位。
ALTER TABLE hall
    ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '影厅维护编辑版本',
    ADD CONSTRAINT ck_hall_version CHECK (version >= 0);

ALTER TABLE seat
    ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '物理座位维护编辑版本',
    ADD CONSTRAINT ck_seat_version CHECK (version >= 0);
