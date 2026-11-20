-- Encore Ticket 数据库表结构
-- 说明：本脚本不使用数据库外键，跨表关系由应用 Service 层负责校验。
--       id 由应用统一生成；create_time、update_time 由应用写入。

CREATE TABLE `sys_user` (
    `id` BIGINT PRIMARY KEY COMMENT '用户主键，由应用生成',
    `username` VARCHAR(64) NOT NULL COMMENT '登录用户名',
    `password` VARCHAR(255) NOT NULL COMMENT '密码密文，不保存明文',
    `nickname` VARCHAR(64) COMMENT '用户昵称',
    `avatar` VARCHAR(255) COMMENT '头像地址',
    `email` VARCHAR(128) COMMENT '电子邮箱',
    `phone` VARCHAR(32) COMMENT '手机号码',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '用户状态：0 正常，1 禁用',
    `auth_version` BIGINT NOT NULL DEFAULT 0 COMMENT '登录态版本',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_sys_user_username` (`username`),
    UNIQUE KEY `uk_sys_user_email` (`email`),
    UNIQUE KEY `uk_sys_user_phone` (`phone`),
    CONSTRAINT `ck_sys_user_auth_version` CHECK (`auth_version` >= 0),
    CONSTRAINT `ck_sys_user_status` CHECK (`status` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

CREATE TABLE `role` (
    `id` BIGINT PRIMARY KEY COMMENT '角色主键，由应用生成',
    `role_code` VARCHAR(50) NOT NULL COMMENT '角色编码，例如 ADMIN、CHECKIN_STAFF',
    `role_name` VARCHAR(50) NOT NULL COMMENT '角色名称',
    `role_desc` VARCHAR(255) COMMENT '角色描述',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_role_code` (`role_code`),
    UNIQUE KEY `uk_role_name` (`role_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

CREATE TABLE `user_role` (
    `id` BIGINT PRIMARY KEY COMMENT '用户角色关系主键',
    `user_id` BIGINT NOT NULL COMMENT '用户 ID，逻辑关联 sys_user.id',
    `role_id` BIGINT NOT NULL COMMENT '角色 ID，逻辑关联 role.id',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_user_role_user_role` (`user_id`, `role_id`),
    KEY `idx_user_role_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关系表';

CREATE TABLE `movie` (
    `id` BIGINT PRIMARY KEY COMMENT '电影主键',
    `title` VARCHAR(255) NOT NULL COMMENT '电影名称',
    `description` TEXT COMMENT '电影简介',
    `release_date` DATE NOT NULL COMMENT '上映日期',
    `duration_minutes` INT NOT NULL COMMENT '影片播放时长（分钟）',
    `publish_at` DATETIME NULL COMMENT '系统自动发行时间，按 UTC 存储',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '电影状态：0 上架，1 下架',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_movie_title_release_date` (`title`, `release_date`),
    KEY `idx_movie_status_publish_at_id` (`status`, `publish_at`, `id`),
    KEY `idx_movie_status_release_date_id` (`status`, `release_date`, `id`),
    KEY `idx_movie_title` (`title`),
    CONSTRAINT `ck_movie_duration_minutes` CHECK (`duration_minutes` > 0),
    CONSTRAINT `ck_movie_version` CHECK (`version` >= 0),
    CONSTRAINT `ck_movie_status` CHECK (`status` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电影信息表';

CREATE TABLE `hall` (
    `id` BIGINT PRIMARY KEY COMMENT '影厅主键',
    `hall_name` VARCHAR(64) NOT NULL COMMENT '影厅名称',
    `hall_no` VARCHAR(32) NOT NULL COMMENT '影厅编号',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '影厅状态：0 启用，1 停用',
    `schedule_version` BIGINT NOT NULL DEFAULT 0 COMMENT '同影厅排片乐观锁版本',
    `version` INT NOT NULL DEFAULT 0 COMMENT '影厅维护编辑版本',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_hall_no` (`hall_no`),
    CONSTRAINT `ck_hall_schedule_version` CHECK (`schedule_version` >= 0),
    CONSTRAINT `ck_hall_version` CHECK (`version` >= 0),
    CONSTRAINT `ck_hall_status` CHECK (`status` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='影厅信息表';

CREATE TABLE `screening` (
    `id` BIGINT PRIMARY KEY COMMENT '场次主键',
    `price` DECIMAL(10, 2) NOT NULL COMMENT '场次单张票价',
    `movie_id` BIGINT NOT NULL COMMENT '电影 ID，逻辑关联 movie.id',
    `hall_id` BIGINT NOT NULL COMMENT '影厅 ID，逻辑关联 hall.id',
    `start_time` DATETIME NOT NULL COMMENT '放映开始时间',
    `admission_open_time` DATETIME NOT NULL COMMENT '允许入场时间，影院本地业务时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '场次配置乐观锁版本',
    `end_time` DATETIME NOT NULL COMMENT '放映结束时间',
    `duration_minutes_snapshot` INT NOT NULL COMMENT '排片时片长快照（分钟）',
    `turnaround_minutes_snapshot` INT NOT NULL DEFAULT 15 COMMENT '场间周转时间快照（分钟）',
    `sale_start_time` DATETIME NOT NULL COMMENT '开始售票时间',
    `sale_end_time` DATETIME NOT NULL COMMENT '停止售票时间',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '场次状态：0 待开售，1 开售，2 停售，3 关闭，4 已结束',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    KEY `idx_screening_movie_id` (`movie_id`),
    KEY `idx_screening_hall_id` (`hall_id`),
    KEY `idx_screening_status_start_id` (`status`, `start_time`, `id`),
    KEY `idx_screening_status_sale_start_id` (`status`, `sale_start_time`, `id`),
    KEY `idx_screening_status_sale_end_id` (`status`, `sale_end_time`, `id`),
    KEY `idx_screening_status_end_id` (`status`, `end_time`, `id`),
    KEY `idx_screening_hall_start_id` (`hall_id`, `start_time`, `id`),
    KEY `idx_screening_movie_status_start_id` (`movie_id`, `status`, `start_time`, `id`),
    CONSTRAINT `ck_screening_duration_minutes` CHECK (`duration_minutes_snapshot` > 0),
    CONSTRAINT `ck_screening_turnaround_minutes` CHECK (`turnaround_minutes_snapshot` >= 0),
    CONSTRAINT `ck_screening_end_duration` CHECK (TIMESTAMPDIFF(SECOND, `start_time`, `end_time`) = `duration_minutes_snapshot` * 60),
    CONSTRAINT `ck_screening_sale_cutoff` CHECK (TIMESTAMPDIFF(SECOND, `sale_end_time`, `start_time`) = 900),
    CONSTRAINT `ck_screening_price` CHECK (`price` > 0),
    CONSTRAINT `ck_screening_admission_before_start` CHECK (`admission_open_time` <= `start_time`),
    CONSTRAINT `ck_screening_version` CHECK (`version` >= 0),
    CONSTRAINT `ck_screening_sale_time_order` CHECK (`sale_end_time` > `sale_start_time`),
    CONSTRAINT `ck_screening_sale_before_start` CHECK (`sale_end_time` < `start_time`),
    CONSTRAINT `ck_screening_end_after_start` CHECK (`end_time` > `start_time`),
    CONSTRAINT `ck_screening_status` CHECK (`status` IN (0, 1, 2, 3, 4))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电影放映场次表';

CREATE TABLE `seat` (
    `id` BIGINT PRIMARY KEY COMMENT '物理座位主键',
    `hall_id` BIGINT NOT NULL COMMENT '影厅 ID，逻辑关联 hall.id',
    `row_number` INT NOT NULL COMMENT '座位排号，从 1 开始',
    `seat_number` INT NOT NULL COMMENT '排内座位号，从 1 开始',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '座位状态：0 启用，1 停用',
    `version` INT NOT NULL DEFAULT 0 COMMENT '物理座位维护编辑版本',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_seat_hall_row_seat` (`hall_id`, `row_number`, `seat_number`),
    CONSTRAINT `ck_seat_row_number` CHECK (`row_number` > 0),
    CONSTRAINT `ck_seat_seat_number` CHECK (`seat_number` > 0),
    CONSTRAINT `ck_seat_version` CHECK (`version` >= 0),
    CONSTRAINT `ck_seat_status` CHECK (`status` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='影厅物理座位表';

CREATE TABLE `orders` (
    `id` BIGINT PRIMARY KEY COMMENT '订单主键',
    `order_no` VARCHAR(32) NOT NULL COMMENT '订单业务编号',
    `user_id` BIGINT NOT NULL COMMENT '下单用户 ID，逻辑关联 sys_user.id',
    `screening_id` BIGINT NOT NULL COMMENT '场次 ID，逻辑关联 screening.id',
    `total_amount` DECIMAL(10, 2) NOT NULL COMMENT '订单总金额',
    `expires_at` DATETIME NOT NULL COMMENT '待支付订单过期时间，创建订单后 15 分钟',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '订单状态：0 待支付，1 已支付，2 已取消，3 已过期，4 退款中，5 已退款',
    `version` INT NOT NULL DEFAULT 0 COMMENT '订单乐观锁版本',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_orders_order_no` (`order_no`),
    KEY `idx_orders_user_id` (`user_id`),
    KEY `idx_orders_screening_id` (`screening_id`),
    KEY `idx_orders_screening_status_expiry_id` (`screening_id`, `status`, `expires_at`, `id`),
    KEY `idx_orders_status_expiry_id` (`status`, `expires_at`, `id`),
    CONSTRAINT `ck_orders_total_amount` CHECK (`total_amount` >= 0),
    CONSTRAINT `ck_orders_status` CHECK (`status` IN (0, 1, 2, 3, 4, 5))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购票订单表';

CREATE TABLE `screening_seat` (
    `id` BIGINT PRIMARY KEY COMMENT '场次座位主键',
    `seat_id` BIGINT NOT NULL COMMENT '物理座位 ID，逻辑关联 seat.id',
    `screening_id` BIGINT NOT NULL COMMENT '场次 ID，逻辑关联 screening.id',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '场次座位状态：0 可售，1 占用，2 已售，3 禁售',
    `order_id` BIGINT COMMENT '占用或售出该座位的订单 ID，逻辑关联 orders.id',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_screening_seat_screening_seat` (`screening_id`, `seat_id`),
    KEY `idx_screening_seat_order_id` (`order_id`),
    CONSTRAINT `ck_screening_seat_status` CHECK (`status` IN (0, 1, 2, 3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场次座位销售状态表';

CREATE TABLE `order_item` (
    `id` BIGINT PRIMARY KEY COMMENT '订单明细主键',
    `order_id` BIGINT NOT NULL COMMENT '订单 ID，逻辑关联 orders.id',
    `screening_seat_id` BIGINT NOT NULL COMMENT '场次座位 ID，逻辑关联 screening_seat.id',
    `unit_price` DECIMAL(10, 2) NOT NULL COMMENT '购票时的单价快照',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_order_item_order_seat` (`order_id`, `screening_seat_id`),
    CONSTRAINT `ck_order_item_unit_price` CHECK (`unit_price` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

CREATE TABLE `payment` (
    `id` BIGINT PRIMARY KEY COMMENT '支付记录主键',
    `payment_no` VARCHAR(64) NOT NULL COMMENT '支付业务编号',
    `order_id` BIGINT NOT NULL COMMENT '订单 ID，逻辑关联 orders.id',
    `amount` DECIMAL(10, 2) NOT NULL COMMENT '支付金额',
    `currency` CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '支付币种',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '支付状态：0 待支付，1 支付成功，2 支付失败',
    `version` INT NOT NULL DEFAULT 0 COMMENT '支付乐观锁版本',
    `attempt_id` VARCHAR(64) NOT NULL COMMENT '当前支付渠道尝试标识',
    `successful_order_id` BIGINT GENERATED ALWAYS AS (
        CASE WHEN `status` = 1 THEN `order_id` ELSE NULL END
    ) STORED COMMENT '成功支付对应订单，用于唯一约束',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_payment_no` (`payment_no`),
    UNIQUE KEY `uk_payment_successful_order_id` (`successful_order_id`),
    KEY `idx_payment_order_id` (`order_id`),
    KEY `idx_payment_attempt_id` (`attempt_id`),
    CONSTRAINT `ck_payment_amount` CHECK (`amount` >= 0),
    CONSTRAINT `ck_payment_status` CHECK (`status` IN (0, 1, 2)),
    CONSTRAINT `ck_payment_version` CHECK (`version` >= 0),
    CONSTRAINT `ck_payment_currency` CHECK (`currency` = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单支付记录表';

CREATE TABLE `payment_notification` (
    `id` BIGINT PRIMARY KEY,
    `provider` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `event_id` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `payment_no` VARCHAR(64) NOT NULL,
    `event_type` VARCHAR(32) NOT NULL,
    `payload_hash` BINARY(32) NOT NULL,
    `processed_at` DATETIME NOT NULL,
    `create_time` DATETIME NOT NULL,
    `attempt_id` VARCHAR(64) NOT NULL COMMENT '渠道通知对应的支付尝试',
    UNIQUE KEY `uk_payment_notification_provider_event` (`provider`, `event_id`),
    KEY `idx_payment_notification_payment_no` (`payment_no`),
    KEY `idx_payment_notification_attempt_id` (`attempt_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付通知去重记录';

CREATE TABLE `payment_audit` (
    `id` BIGINT PRIMARY KEY,
    `payment_id` BIGINT NOT NULL,
    `order_id` BIGINT NOT NULL,
    `operator_id` BIGINT NULL,
    `operation` VARCHAR(64) NOT NULL,
    `from_status` TINYINT NULL,
    `to_status` TINYINT NULL,
    `source` VARCHAR(32) NOT NULL,
    `request_id` VARCHAR(64) NULL,
    `event_id` VARCHAR(128) NULL,
    `remark` VARCHAR(255) NULL,
    `create_time` DATETIME NOT NULL,
    KEY `idx_payment_audit_payment_time` (`payment_id`, `create_time`, `id`),
    KEY `idx_payment_audit_order_id` (`order_id`),
    CONSTRAINT `ck_payment_audit_source` CHECK (`source` IN ('USER', 'ADMIN', 'CHANNEL', 'SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付操作审计记录';

CREATE TABLE `ticket` (
    `id` BIGINT PRIMARY KEY COMMENT '票券主键',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '票券状态：0 有效，1 已核销，2 已作废',
    `order_item_id` BIGINT NOT NULL COMMENT '订单明细 ID，逻辑关联 order_item.id',
    `ticket_code` VARCHAR(128) NOT NULL COMMENT '票券码，供用户扫码入场',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_ticket_order_item` (`order_item_id`),
    UNIQUE KEY `uk_ticket_code` (`ticket_code`),
    CONSTRAINT `ck_ticket_status` CHECK (`status` IN (0, 1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电子票券表';

CREATE TABLE `refund` (
    `id` BIGINT PRIMARY KEY COMMENT '退款记录主键',
    `refund_no` VARCHAR(64) NOT NULL COMMENT '退款业务编号',
    `order_id` BIGINT NOT NULL COMMENT '订单 ID，逻辑关联 orders.id',
    `payment_id` BIGINT NOT NULL COMMENT '支付记录 ID，逻辑关联 payment.id',
    `amount` DECIMAL(10, 2) NOT NULL COMMENT '退款金额',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '退款状态：0 待处理，1 退款成功，2 退款失败',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    `source` VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
    `attempt_no` INT NOT NULL DEFAULT 1,
    `next_run_at` DATETIME(6) NULL,
    `lease_token` VARCHAR(64) NULL,
    `lease_until` DATETIME(6) NULL,
    CONSTRAINT `ck_refund_source` CHECK (`source` IN ('USER','LATE_PAYMENT','LEGACY')),
    CONSTRAINT `ck_refund_attempt_no` CHECK (`attempt_no` > 0),
    KEY `idx_refund_pending_run` (`status`,`next_run_at`,`id`),
    UNIQUE KEY `uk_refund_no` (`refund_no`),
    UNIQUE KEY `uk_refund_payment_id` (`payment_id`),
    KEY `idx_refund_order_id` (`order_id`),
    CONSTRAINT `ck_refund_amount` CHECK (`amount` >= 0),
    CONSTRAINT `ck_refund_status` CHECK (`status` IN (0, 1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单退款记录表';

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

CREATE TABLE `ticket_verification` (
    `id` BIGINT PRIMARY KEY COMMENT '核销记录主键',
    `ticket_id` BIGINT NOT NULL COMMENT '票券 ID，逻辑关联 ticket.id',
    `operator_id` BIGINT NOT NULL COMMENT '核销人员 ID，逻辑关联 sys_user.id',
    `device_code` VARCHAR(64) NOT NULL COMMENT '扫码设备编号，不关联独立设备表',
    `verified_at` DATETIME NOT NULL COMMENT '实际核销时间',
    `create_time` DATETIME NOT NULL COMMENT '核销记录创建时间',
    UNIQUE KEY `uk_ticket_verification_ticket_id` (`ticket_id`),
    KEY `idx_ticket_verification_verified_at` (`verified_at`),
    CONSTRAINT `ck_ticket_verification_device_code` CHECK (CHAR_LENGTH(TRIM(`device_code`)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='票券成功核销记录表';

-- 核销成功幂等快照，使用二进制排序保留键大小写。
CREATE TABLE `api_idempotency` (
    `operator_id` BIGINT NOT NULL,
    `operation` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `idempotency_key` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `request_digest` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `response_json` JSON NULL,
    `expires_at` DATETIME(6) NOT NULL,
    UNIQUE KEY `uk_api_idempotency_scope` (`operator_id`, `operation`, `idempotency_key`),
    KEY `idx_api_idempotency_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API成功幂等快照';

-- 角色种子数据由 db/migration/V2__seed_roles.sql 写入：role.id 固定为
-- 1=ADMIN、2=CHECKIN_STAFF、3=USER，应用侧常量见 common.constant.RoleConstant。
-- 调整这些主键需要同时新增迁移并更新常量。
