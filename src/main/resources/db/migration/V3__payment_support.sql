-- Payment support extensions. V1 and V2 remain immutable.
ALTER TABLE `orders`
    ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '订单乐观锁版本' AFTER `status`;

ALTER TABLE `payment`
    ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '支付乐观锁版本' AFTER `status`,
    ADD COLUMN `currency` CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '支付币种' AFTER `amount`,
    ADD COLUMN `successful_order_id` BIGINT GENERATED ALWAYS AS (
        CASE WHEN `status` = 1 THEN `order_id` ELSE NULL END
    ) STORED COMMENT '成功支付对应订单，用于唯一约束' AFTER `version`,
    ADD UNIQUE KEY `uk_payment_successful_order_id` (`successful_order_id`),
    ADD CONSTRAINT `ck_payment_version` CHECK (`version` >= 0),
    ADD CONSTRAINT `ck_payment_currency` CHECK (`currency` = 'CNY');

CREATE TABLE `payment_notification` (
    `id` BIGINT PRIMARY KEY,
    `provider` VARCHAR(32) NOT NULL,
    `event_id` VARCHAR(128) NOT NULL,
    `payment_no` VARCHAR(64) NOT NULL,
    `event_type` VARCHAR(32) NOT NULL,
    `payload_hash` BINARY(32) NOT NULL,
    `processed_at` DATETIME NOT NULL,
    `create_time` DATETIME NOT NULL,
    UNIQUE KEY `uk_payment_notification_provider_event` (`provider`, `event_id`),
    KEY `idx_payment_notification_payment_no` (`payment_no`)
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
