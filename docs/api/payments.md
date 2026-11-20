# 支付接口

遵循 [API 通用契约](common-contract.md)。成功返回 `ApiResponse.data`，错误返回 `code/message/data/requestId`，响应包含 `X-Request-Id`。ID 和金额均为字符串，技术时间为 UTC RFC 3339，币种固定 `CNY`。支付状态为 `PENDING/SUCCESS/FAILED`。

## 入口及权限

| 方法 | 路径 | 权限 | data |
|---|---|---|---|
| POST | `/orders/{orderId}/payments` | 已登录、订单本人 | PaymentVO |
| GET | `/orders/{orderId}/payments` | 已登录、订单本人 | orderId、orderStatus、items |
| GET | `/admin/payments` | ADMIN | items、page、pageSize、total |
| GET | `/admin/payments/{paymentId}` | ADMIN | payment、order、refund、audits、notifications |
| POST | `/admin/payments/{paymentId}/retry` | ADMIN | 新 PaymentVO |

路径 ID 为正 Long。两个 POST 无请求体，必须提供 `Idempotency-Key`；金额、币种、支付结果和操作者由服务端决定。普通用户没有修改支付结果的入口，管理员也不能直接设置成功或失败。当前结果处理使用内部模拟渠道，不注册公开的 mock-success/mock-fail/mock-notify HTTP 接口。

## 支付与重试

首次付款创建 PENDING；已有 PENDING 时返回当前记录，不再次扣款；明确 FAILED 后用户换新幂等键重付，复用原支付记录并创建新渠道尝试。SUCCESS 在订单仍为已支付时返回成功记录。结果未知保持 PENDING，通过 GET 查询，不能自行判作失败。

管理员仅可重试订单当前的 FAILED 支付，并且订单仍可付款；重试创建新支付记录，保留旧失败记录和审计。用户和管理员都不能绕过订单状态或截止限制。实际截止为下单后 15 分钟、`orders.expires_at` 与场次售票截止的较早值，恰好达到截止时刻即禁止付款；人工提前停售不缩短既有订单期限。

同一次网络重试复用原键。幂等范围包括认证操作者、业务操作和请求摘要；相同键及内容重放首次保存的业务响应，相同键不同目标返回 409。重放仍先验证权限。GET 返回当前状态，与 POST 历史响应快照语义不同。成功响应快照与业务写入在同一事务提交，成功后保留 24 小时。支付清理任务每次最多删除 500 条已到期支付快照，不删除核销快照、支付、通知或审计；间隔由 `encore.payment.cleanup-delay-ms` 配置，默认 60000 毫秒。快照到期不取消数据库业务防重约束。

合法重复渠道事件成功确认，不重复出票；同一 provider/eventId 携带不同内容返回冲突。通知区分渠道尝试，旧尝试失败不得覆盖新尝试。已成功记录不会被失败结果覆盖。到期或取消后收到成功时保留成功支付、订单进入 REFUNDING 并创建待处理补偿退款，不出票、不重新占座；创建退款记录不代表资金已退回。退款持久化恢复与自动模拟执行见 refunds.md；不包含第二笔成功扣款补偿。取消迟到成功须保留取消历史，补偿不得释放其他订单重占/已售座位；这些资金补偿要求不等于取消入口已经实现。

订单四入口及取消、创建幂等规则见[订单规格](orders.md)。`PENDING` 禁止用户主动取消或新退款申请，但不阻止原期限过期。订单 GET 的派生 `EXPIRED` 不写库；当前支付 GET 的 `orderStatus` 按持久化状态映射，不宣称已同步订单 GET 派生口径，统一展示需在对应实施时核对。

## 查询参数

管理列表支持下列 Query；固定 `create_time DESC,id DESC` 排序。

| 参数 | 约束 |
|---|---|
| page | 从 1 开始，默认 1 |
| pageSize | 1–100，默认 20 |
| paymentStatus | PENDING、SUCCESS、FAILED |
| orderId | 正 Long |
| paymentNo | 支付编号精确匹配 |
| createdFrom | UTC Instant，包含起点 |
| createdTo | UTC Instant，不包含终点 |

起点不能晚于终点。列表项字段同 PaymentVO。PaymentVO 包含 `paymentId/paymentNo/orderId/amount/currency/status/createdAt/updatedAt`，例如：

```json
{
  "data": {
    "paymentId": "10001",
    "paymentNo": "Pexample",
    "orderId": "9001",
    "amount": "88.00",
    "currency": "CNY",
    "status": "PENDING",
    "createdAt": "2026-10-19T12:00:00Z",
    "updatedAt": "2026-10-19T12:00:00Z"
  }
}
```

管理详情包含支付对象、订单安全摘要、可空退款摘要、审计列表和已处理通知元数据。订单摘要包含 `orderId/orderNo/screeningId/status/totalAmount/currency/expiresAt`；此 expiresAt 是订单字段，不单独承诺已合并场次售票截止。退款摘要包含 `refundId/refundNo/amount/currency/status/createdAt/updatedAt`。通知仅返回 `provider/eventId/eventType/processedAt/createdAt`，不返回完整报文、payload hash 或票券凭证。审计包含操作、前后状态、来源、请求/事件标识、安全备注和创建时间。

## 错误

| HTTP | code | 场景 |
|---|---|---|
| 400 | INVALID_ARGUMENT | 非法 ID、分页、时间、幂等键 |
| 401 | UNAUTHORIZED | 未登录或登录失效 |
| 403 | FORBIDDEN | 非管理员访问管理入口 |
| 404 | RESOURCE_NOT_FOUND | 订单不存在或不属于当前用户 |
| 404 | PAYMENT_NOT_FOUND | 支付不存在 |
| 409 | PAYMENT_STATE_CONFLICT | 状态或版本冲突 |
| 409 | PAYMENT_IDEMPOTENCY_CONFLICT | 同键不同请求或同事件不同内容 |
| 409 | PAYMENT_ORDER_NOT_PAYABLE | 订单已截止或当前状态不可支付 |
| 409 | PAYMENT_RETRY_NOT_ALLOWED | 不满足管理员重试条件 |
| 422 | PAYMENT_AMOUNT_MISMATCH | 支付与订单/渠道金额币种不匹配 |
| 500 | INTERNAL_ERROR | 未预期错误，不返回 SQL 或内部堆栈 |

## 测试专用模拟入口

仅 `(local | test | loadtest) & !prod` 且显式设置 `encore.payment.simulation.enabled=true` 时注册，默认关闭；混合启用 prod 时仍不注册。两个入口都要求现有 ADMIN 身份，用户下单及支付仍使用订单本人身份。

- `POST /admin/test/payments/events`：请求 `{ "paymentNo": "...", "attemptId": "...", "eventId": "...", "result": "SUCCESS" }`，result 只允许 SUCCESS/FAILED，eventId 为 1～128 个可打印 ASCII 字符且不能全空白。渠道固定 SIM，金额币种由后端读取。调用既有结果处理事务，提交成功后返回 200；200 表示事件确认，不保证支付为 SUCCESS。同一事件超时重试须保留 eventId 和全部内容。
- `GET /admin/test/payments/{paymentNo}/attempts?limit=20&cursor=...`：limit 为 1～100，响应 data 含 items 和 nextCursor；items 仅含 paymentNo、attemptId、createdAt、current。按审计 ID 升序游标分页，nextCursor 为 null 表示结束；单页使用只读一致性快照，跨页遇到并发重付时应重新查询。历史时间来自 ATTEMPT_STARTED 审计；无审计的当前迁移尝试时间为 null，不伪造时间。已被覆盖且从未记录审计的历史迁移尝试无法恢复，不承诺返回不存在的历史证据。

外部脚本为 `scripts/payment_simulation.py`，仅使用 Python 标准库。必须显式提供精确 origin 白名单；Token 从 PAYMENT_USER_TOKEN、PAYMENT_ADMIN_TOKEN 等环境变量读取，不接受命令行凭据。任务 JSON 数组元素为 `{ "screeningId": "10001", "seatIds": ["30001"], "scenario": "success" }`，seatIds 是场次座位 ID，不是影厅座位 ID。

```bash
python scripts/payment_simulation.py --target http://127.0.0.1:8080 --allow-origin http://127.0.0.1:8080 --tasks tasks.json
python -B -m unittest discover -s scripts -p test_payment_simulation.py -v
```

支持 success、failed、retry、old-failure、replay、duplicate、late-success；迟到场景通过 --delay 等待真实到期，不修改服务端时钟，延迟不足会阻断投递。默认并发 1、全局每秒 1 请求、启动窗口 30 秒，已启动任务排空且不循环使用座位。任务会创建真实订单，失败订单不自动取消；只对明确授权的隔离测试环境执行，不自动执行高负载。

脚本通过真实下单入口进入支付链路，订单不可用即失败，不回退预置订单。HTTP 契约测试不等于真实数据库 E2E；真实越权、事务回滚、售座并发与隔离库验收需要单独执行，报告不能将 REFUNDING 当作退款完成。额外成功收款补偿仍不在本次范围。

## 持久化与验证

累计表结构见 [schema.sql](../database/schema.sql)，升级前运行 [只读预检](../database/payment-upgrade-preflight.sql)，按脚本说明分别执行 V3 前和 V22 前检查。历史迁移不改写；多条 MySQL DDL 不保证整体回滚，失败需核对实际结构后前向修复。

V22 要求首次 INSERT 前设置非空 `payment.attempt_id`，不能依赖 insert 后 UPDATE 补齐。单订单成功支付、单明细出票、渠道通知和 API 幂等作用域由数据库唯一约束保护。

`PaymentPersistenceMySqlTests` 显式启用 `flyway.poc.enabled=true`，仅接受空的 `encore_ticket_flyway_poc_payment_*` 专用库，连接通过 `flyway.poc.url/username` 指定，密码经 `FLYWAY_POC_PASSWORD` 或 `MYSQL_PASSWORD` 提供。测试保留结果库供检查，重复运行应另建新的空专用库。缺少凭据或连接不可用时报告 skip；迁移、SQL 或断言失败报告失败。覆盖累计迁移与字段对照、V22 旧数据回填、首次 attempt 非空、唯一性、事务回滚、数据库并发和到期清理；这不等同于完整支付 Service 或购票 E2E 验收。
