# Minimal ticket verification audit model

第一版采用单独的 `ticket_verification` 表记录成功核销，不新增设备表，也不把核销人、设备和核销时间直接写入 `ticket`。核销记录是不可变审计数据，以 `ticket_id` 唯一约束保证一张票只能成功核销一次；设备暂以 `device_code` 字符串保存。失败扫码不写入业务表，只写入脱敏结构化日志和指标，避免在没有风控与合规需求时引入过重的领域模型。

## Revision: application-managed references and simple indexes

本项目不在 `ticket_verification` 上建立数据库外键，`ticket_id` 与 `operator_id` 的存在性和可访问性由 Service 层在事务中校验，以保持数据库写入路径简单。索引只采用单字段索引：`ticket_id` 的唯一约束索引，以及按核销时间查询所需的 `verified_at` 索引；不建立联合索引。
