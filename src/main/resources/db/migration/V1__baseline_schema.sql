-- Flyway V1 initial schema. The canonical design source is
-- docs/database/schema.sql. Keep this migration immutable after release.
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
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_sys_user_username` (`username`),
    UNIQUE KEY `uk_sys_user_email` (`email`),
    UNIQUE KEY `uk_sys_user_phone` (`phone`),
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
    `release_date` DATE COMMENT '上映日期',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '电影状态：0 上架，1 下架',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    CONSTRAINT `ck_movie_status` CHECK (`status` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电影信息表';

CREATE TABLE `hall` (
    `id` BIGINT PRIMARY KEY COMMENT '影厅主键',
    `hall_name` VARCHAR(64) NOT NULL COMMENT '影厅名称',
    `hall_no` VARCHAR(32) NOT NULL COMMENT '影厅编号',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '影厅状态：0 启用，1 停用',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_hall_no` (`hall_no`),
    CONSTRAINT `ck_hall_status` CHECK (`status` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='影厅信息表';

CREATE TABLE `screening` (
    `id` BIGINT PRIMARY KEY COMMENT '场次主键',
    `price` DECIMAL(10, 2) NOT NULL COMMENT '场次单张票价',
    `movie_id` BIGINT NOT NULL COMMENT '电影 ID，逻辑关联 movie.id',
    `hall_id` BIGINT NOT NULL COMMENT '影厅 ID，逻辑关联 hall.id',
    `start_time` DATETIME NOT NULL COMMENT '放映开始时间',
    `end_time` DATETIME NOT NULL COMMENT '放映结束时间',
    `sale_start_time` DATETIME NOT NULL COMMENT '开始售票时间',
    `sale_end_time` DATETIME NOT NULL COMMENT '停止售票时间',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '场次状态：0 待开售，1 开售，2 停售，3 关闭，4 已结束',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    KEY `idx_screening_movie_id` (`movie_id`),
    KEY `idx_screening_hall_id` (`hall_id`),
    CONSTRAINT `ck_screening_price` CHECK (`price` >= 0),
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
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_seat_hall_row_seat` (`hall_id`, `row_number`, `seat_number`),
    CONSTRAINT `ck_seat_row_number` CHECK (`row_number` > 0),
    CONSTRAINT `ck_seat_seat_number` CHECK (`seat_number` > 0),
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
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_orders_order_no` (`order_no`),
    KEY `idx_orders_user_id` (`user_id`),
    KEY `idx_orders_screening_id` (`screening_id`),
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
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '支付状态：0 待支付，1 支付成功，2 支付失败',
    `create_time` DATETIME NOT NULL COMMENT '创建时间',
    `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY `uk_payment_no` (`payment_no`),
    KEY `idx_payment_order_id` (`order_id`),
    CONSTRAINT `ck_payment_amount` CHECK (`amount` >= 0),
    CONSTRAINT `ck_payment_status` CHECK (`status` IN (0, 1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单支付记录表';

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
    UNIQUE KEY `uk_refund_no` (`refund_no`),
    UNIQUE KEY `uk_refund_payment_id` (`payment_id`),
    KEY `idx_refund_order_id` (`order_id`),
    CONSTRAINT `ck_refund_amount` CHECK (`amount` >= 0),
    CONSTRAINT `ck_refund_status` CHECK (`status` IN (0, 1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单退款记录表';

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

