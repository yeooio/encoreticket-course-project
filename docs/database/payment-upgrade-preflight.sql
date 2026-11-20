-- 支付升级只读预检：停写并备份后，在目标数据库手动运行。
-- 每个结果集必须为空；发现冲突时停止升级、人工核查，不删除或改写支付历史。
-- 适用于 V1/V2 升 V3 的 payment/orders；最后两项用于已安装 V3、升级 V22 前。
-- 先核对实际 Flyway history；MySQL DDL 非整体事务，失败后先核对结构再前向修复。
SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;

-- 同订单多次成功：V3 唯一索引将拒绝此类数据。
SELECT order_id, COUNT(*) AS success_count, GROUP_CONCAT(id ORDER BY id) AS payment_ids
FROM payment WHERE status = 1 GROUP BY order_id HAVING COUNT(*) > 1;

-- 孤立支付引用。
SELECT p.id AS payment_id, p.order_id FROM payment p
LEFT JOIN orders o ON o.id = p.order_id WHERE o.id IS NULL;

-- 金额与订单不一致，不能自动修正历史收款金额。
SELECT p.id AS payment_id, p.order_id, p.amount, o.total_amount
FROM payment p JOIN orders o ON o.id = p.order_id WHERE p.amount <> o.total_amount;

-- 非法状态或金额。
SELECT id, order_id, amount, status FROM payment
WHERE amount < 0 OR status NOT IN (0, 1, 2);

-- 以下仅在 V3 已安装时运行；V22 将 provider/event_id 转为 ASCII 二进制排序。
-- 非 ASCII 数据必须人工处理，避免字符集转换失败或替换。
SELECT id, provider, event_id FROM payment_notification
WHERE HEX(provider) <> HEX(CONVERT(provider USING ascii))
   OR HEX(event_id) <> HEX(CONVERT(event_id USING ascii));

-- V22 对未关联通知生成 legacy-event-<id>，仍列出断链供人工核查。
SELECT n.id AS notification_id, n.payment_no FROM payment_notification n
LEFT JOIN payment p ON p.payment_no = n.payment_no WHERE p.id IS NULL;
