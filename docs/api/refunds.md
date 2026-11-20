# 退款接口（2026-10-20 已批准契约）

遵循 common-contract.md：认证、ApiResponse、PageResponse、错误追踪；ID、金额为字符串，币种 CNY，技术时间 UTC RFC3339。只支持全额原路整单退款，金额取服务器校验一致的成功支付、订单和明细总额，不接受金额、账户、操作者或结果字段。无审核、不可撤回，受理后自动模拟退款；受理成功不等于资金成功。

## 用户接口

- `POST /orders/{orderId}/refunds`：本人，单个 Idempotency-Key（1–128 位 `[A-Za-z0-9_-]`），无 Body、无 Query。200 返回 RefundVO。
- `GET /orders/{orderId}/refunds`：本人，无 Body、无 Query；200 返回 `{orderId, orderStatus, items: [RefundVO]}`。不存在或非本人统一 404 RESOURCE_NOT_FOUND。

新申请须订单 PAID、成功支付及完整明细/有效票券金额一致、任一 USED 拒绝。按 screening → orders → ticket ID 升序锁定，锁后取 Clock；仅 `now < startTime(Asia/Shanghai)-15min` 允许，等于拒绝。已有退款和成功快照先于截止资格判断，换键不创建第二笔退款。受理同事务保存 PENDING、来源 USER、订单 REFUNDING、尝试/审计和成功快照。失败仍禁止入场。

RefundVO：`refundId/refundNo/orderId/paymentId/amount/currency/status/source/createdAt/updatedAt`；status 为 PENDING/SUCCESS/FAILED；source 为 USER/LATE_PAYMENT/LEGACY。用户不返回渠道标识、内部失败详情或票码。

## 管理接口（仅 ADMIN）

- `GET /admin/refunds`：page 默认1，pageSize 默认20、1–100；可选 status、source、orderId（正Long）、createdFrom/createdTo（带偏移时间，[from,to)，from<to）。固定 create_time DESC,id DESC；返回分页 RefundVO。
- `GET /admin/refunds/{refundId}`：返回 `{refund, attempts, audits}`；尝试仅含序号、状态、创建/更新时间，审计含动作、操作员ID、创建时间；不返回渠道完整报文或账户。
- `POST /admin/refunds/{refundId}/process`：要求幂等键，唯一 Body 字段 `action`，仅 RETRY 或 CHECK。RETRY 只允许 FAILED，复用退款并新建尝试；CHECK 只允许 PENDING，安排查询现有尝试而非重新退钱。200 返回受理快照 RefundVO。禁止手工指定 SUCCESS、金额或目标账户。

写幂等独立操作 refund-apply:v1/refund-process:v1；作用域为操作者+操作+键，摘要包含目标及动作。权限先校验；同键同内容重放首次响应，同键异参409 IDEMPOTENCY_CONFLICT。快照24小时，过期不取消退款业务唯一性；退款清理每轮最多500条，不清理支付/核销快照或审计。

## 自动执行与恢复

持久化尝试即待处理集合，定时有界扫描；先提交领取/租约，再在无业务锁的事务外调用模拟渠道，最后新事务保存结果和联动。稳定退款号是资金幂等标识，尝试区分明确失败后的轮次。未知/异常维持 PENDING，恢复只能查询已发尝试；查询确认未受理时才可用同一标识发起，不得因租约到期建立新资金操作。模拟渠道结果也持久化，重复结果不会二次退款；成功不被失败覆盖，已知旧尝试的成功同样收敛。

成功事务持共同订单锁，VALID 票转 VOID，订单 REFUNDED；主动退款只释放本订单 SOLD 座位，关联矛盾不强行放座，保留 PENDING 待核查。CANCELLED/EXPIRED 迟到成功自动补偿，不受退票截止限制、不补票，不释放他人座位；取消历史由支付审计及退款来源保留。存量退款标为 LEGACY，不能擅自当作主动申请；存量 PENDING 先核查，不自动重新发起资金操作。

不含场次批量退款、第二笔成功扣款补偿、真实支付平台或公开模拟控制入口。

## 错误

400 INVALID_ARGUMENT：非法ID/分页/时间/键、未知字段、请求体或参数；401 UNAUTHORIZED；403 FORBIDDEN；404 RESOURCE_NOT_FOUND；409 REFUND_STATE_CONFLICT（状态不允许）、REFUND_CUTOFF_REACHED（达到截止）、TICKET_ALREADY_USED、IDEMPOTENCY_CONFLICT；500 INTERNAL_ERROR（金额/票券/必要关联不完整及本地一致性异常）。渠道未知不映射成明确失败。

## 验证边界

本次已批准 TDD 边界为用户申请/查询 HTTP、退款处理服务、MySQL 并发。真实数据库测试仅使用显式启用的空隔离库，不操作业务库；未启用或连接不可用须报告跳过，不能视为通过。
