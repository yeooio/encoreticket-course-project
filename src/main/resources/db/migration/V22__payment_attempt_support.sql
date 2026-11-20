-- 支付渠道尝试标识，区分用户复用支付单后的不同扣款尝试。
ALTER TABLE `payment`
    ADD COLUMN `attempt_id` VARCHAR(64) NULL COMMENT '当前支付渠道尝试标识' AFTER `version`,
    ADD KEY `idx_payment_attempt_id` (`attempt_id`);

-- 旧支付记录没有渠道尝试审计，生成稳定的迁移标识，避免新通知无法区分空尝试。
UPDATE `payment`
SET `attempt_id` = CONCAT('legacy-', CAST(`id` AS CHAR))
WHERE `attempt_id` IS NULL;

ALTER TABLE `payment`
    MODIFY COLUMN `attempt_id` VARCHAR(64) NOT NULL COMMENT '当前支付渠道尝试标识';

ALTER TABLE `payment_notification`
    ADD COLUMN `attempt_id` VARCHAR(64) NULL COMMENT '渠道通知对应的支付尝试' AFTER `create_time`,
    ADD KEY `idx_payment_notification_attempt_id` (`attempt_id`),
    MODIFY COLUMN `provider` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY COLUMN `event_id` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;

UPDATE `payment_notification` n
JOIN `payment` p ON p.payment_no = n.payment_no
SET n.attempt_id = p.attempt_id
WHERE n.attempt_id IS NULL;

UPDATE `payment_notification`
SET `attempt_id` = CONCAT('legacy-event-', CAST(`id` AS CHAR))
WHERE `attempt_id` IS NULL;

ALTER TABLE `payment_notification`
    MODIFY COLUMN `attempt_id` VARCHAR(64) NOT NULL COMMENT '渠道通知对应的支付尝试';
