-- 角色种子数据。
-- 角色主键固定为 1=ADMIN、2=CHECKIN_STAFF、3=USER，应用侧常量见 common.constant.RoleConstant。
-- 使用 INSERT IGNORE 保证重复执行不会产生重复行；审计时间按 UTC 写入。
INSERT IGNORE INTO `role` (`id`, `role_code`, `role_name`, `role_desc`, `create_time`, `update_time`)
VALUES (1, 'ADMIN', '管理员', '维护电影、影厅、场次和系统配置，并处理授权的运营管理动作', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       (2, 'CHECKIN_STAFF', '检票人员', '在入场场景中扫描并核销票券', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
       (3, 'USER', '普通用户', '注册后获得的默认角色，代表普通购票观众', UTC_TIMESTAMP(), UTC_TIMESTAMP());
