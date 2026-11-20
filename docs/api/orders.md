# 本人订单接口规格

## 1. 规格地位与范围

本规格整理 2026-10-20 已确认的订单业务约定，遵循 [通用契约](common-contract.md)、[状态矩阵](../domain/state-transitions.md)及 ADR 0001/0003/0004/0005。**这是正式规格；实施及实测事实见第 10 节。** 原文档阶段只记录契约；2026-10-20 用户随后授权实现，采纳本文建议字段、分页、HTTP 与错误码，不新增 `/users/me/orders` 别名、不删除既有入口。

本规格只有四个订单入口，均需 Bearer 认证，身份取服务端登录态；管理员也不能借这些入口跨用户操作：

| 方法 | 路径 | 用途 | 建议成功 HTTP |
|---|---|---|---|
| POST | `/orders` | 创建订单并原子占座 | 201 |
| GET | `/orders` | 本人订单列表 | 200 |
| GET | `/orders/{orderId}` | 本人订单详情 | 200 |
| POST | `/orders/{orderId}/cancel` | 主动取消待支付订单 | 200 |

支付和退款关联入口分别见 [payments.md](payments.md)、[refunds.md](refunds.md)，不计入上述四入口。`GET /users/me/orders` 与 `GET /orders` 的重复入口处理**未决**；前者暂不纳入本次四入口，不据此删除既有目录条目或新增别名。

以下座位、期限、状态、幂等业务规则均已确认。普通字段、分页、排序、HTTP、参数格式与错误码原标为建议默认；2026-10-20 实施授权已明确采纳，不改变已确认业务规则。

## 2. 状态与时间

持久化/领域 `WAIT_PAY` 对外映射为 `PENDING_PAYMENT`，其余为 `PAID/CANCELLED/EXPIRED/REFUNDING/REFUNDED`；不对外返回数据库数字，也不新增审核或订单级退款失败状态。

实际付款截止 `deadline = min(createdAt + 15分钟, orders.expires_at, 场次售票截止绝对时间)`。场次售票截止为开场前 15 分钟，按 `Asia/Shanghai` 转为时间点；`now >= deadline` 即到期。管理员提前停售只阻止新订单，不缩短既有订单付款期限。技术时间按 UTC RFC 3339 输出 `Z`，场次时间带 `+08:00`。

建议响应 `expiresAt` 表达实际付款截止，不直接承诺等于数据库列；数据库原字段语义不变。GET 每请求取一次服务端时间：持久化 `WAIT_PAY` 且已到期时展示 `EXPIRED`，不提供付款或主动取消操作，其他合法状态按映射展示。列表状态筛选与详情使用相同派生口径。**GET 不写库、不释放座位，派生过期不表示座位已释放**；实际过期由任务或相关写请求提交，新下单不能依赖 GET 代替清理。未知状态或已确认本人订单的必要关联缺失应报内部错误，不伪装成正常订单。

## 3. 创建订单 `POST /orders`

### 3.1 输入

要求 `Content-Type: application/json` 和单个 `Idempotency-Key`。建议键格式沿用交易接口：1～128 位 `[A-Za-z0-9_-]`，区分大小写；ID 使用正 Long 十进制字符串，不接受数字类型、物理座位 ID 或展示编号。

```json
{
  "screeningId": "10001",
  "screeningSeatIds": ["30001", "30002"]
}
```

仅这两个业务字段。金额、状态、过期时间、用户、订单号均由服务端确定；建议未知字段返回参数错误，而非信任客户端值。选座使用 `GET /screenings/{screeningId}/seats` 返回的 `seats[].screeningSeatId`，不是同项的 `seatId`；查询可售不构成预留保证。

### 3.2 已确认业务与事务约束

1. 每单同一场次 1～6 个**不同**座位。空列表、超过 6 个或重复 ID 返回参数错误；不自动去重，不创建订单、不占座。
2. 后端逐一校验场次座位归属、销售状态允许占用及物理座位可用性，不信任客户端归属/可售声明。场次购买资格沿用已有场次可售规则，不另设限购、价格或销售政策。
3. 订单、全部明细与全部 `AVAILABLE → HELD` 在同一事务完成，任一不可售则整单失败，绝不部分占座。与物理座位停用互斥并重查，不能发生停用后仍成功占座。
4. 同一用户同一场次最多一个未达到实际截止的 `WAIT_PAY` 订单；不同场次互不限制，已支付不占名额，也不是每人每场累计限购 6 张。已有订单未到期拒绝第二单，引导继续付款或取消；已到期则先实际提交过期及本订单剩余占座释放，再允许创建新单。并发也必须保证此约束，不能只做未保护查询。
5. 创建后不可改场次、座位或座位数量，不提供改单/换座入口。调整需满足取消条件后取消原单，再以新键下单；新订单按当时规则重新计算价格与期限，旧单保留历史，不保证原座位或目标座位仍可购买。`PENDING` 禁止主动取消，不能借改单绕过。
6. 价格由服务端依现有固定场次票价计算，明细保存 `order_item.unit_price`，总额使用 `orders.total_amount`。本规格不承诺新增价格字段、电影/影厅/座位名称历史快照或数据库迁移。

### 3.3 创建幂等（已确认）

- 作用域为当前用户、同一创建操作和幂等键；身份校验不能由键代替。
- 同键同内容在成功快照保留期内只重放首次成功创建响应，不新建、不重新占座；原单取消、过期或已进入其他状态也一样。先识别合法重放，不能将其误判为第二个待支付订单。
- 内容含 `screeningId` 和规范化的座位集合。**先拒绝重复座位，再排序生成摘要**；仅排列不同视为同内容，同键不同场次或座位集合返回冲突。
- 首次成功创建的业务响应快照保留 24 小时，重放不续期；达到保留截止不再保证重放，旧键可作为新请求重新通过全部校验，可能创建新订单。真正的新购票始终用新键，不跨保留期持续自动重试。
- 快照不表示当前可支付，当前状态须查 GET。建议重放同一业务响应体和创建 HTTP 201；本次请求的追踪头重新生成，不将首次 requestId 当成业务快照。成功业务与快照须原子保存，不承诺失败请求的长期重放或另加保留期。

## 4. 查询字段与分页（建议默认）

### 4.1 公共响应

成功使用 `{"data": ...}`；ID/金额为字符串，币种 `CNY`，金额固定两位小数。建议创建与详情均返回下列 OrderDetail，列表返回其摘要（不含 `items`）。名称、时间等关联展示字段不是新增历史快照承诺，不能据此认为会永久保留下单时文案。

| 字段 | 含义 |
|---|---|
| `orderId/orderNo` | 内部 ID / 业务编号 |
| `screeningId/movieId/movieTitle/hallId/hallName` | 场次及展示关联 |
| `startTime/endTime` | 场次时间，`+08:00` |
| `status` | 当前有效展示状态；创建重放除外，为首次快照 |
| `seatCount` | 明细数量 |
| `totalAmount/currency` | 订单原金额，不因退款改成零 |
| `expiresAt` | 实际付款截止，UTC `Z` |
| `createdAt/updatedAt` | 技术时间，UTC `Z`；派生过期不改更新时间 |
| `items` | 详情明细，建议按场次座位 ID 升序 |

`items[]` 建议含 `orderItemId/screeningSeatId/seatId/rowNumber/seatNumber/unitPrice`，不返回票券码、支付渠道凭据或他人信息。票券由独立票券接口查询。

精简结构示例（仅展示基础字段，不承诺已存在对应 VO）：

```json
{
  "data": {
    "orderId": "9001",
    "orderNo": "Oexample",
    "screeningId": "10001",
    "status": "PENDING_PAYMENT",
    "seatCount": 2,
    "totalAmount": "79.80",
    "currency": "CNY",
    "expiresAt": "2026-10-20T11:15:00Z",
    "createdAt": "2026-10-20T11:00:00Z",
    "updatedAt": "2026-10-20T11:00:00Z"
  }
}
```

### 4.2 `GET /orders`

无 Body。建议 Query：`page` 默认 1，`pageSize` 默认 20、范围 1～100；可选 `status`（对外六种字符串）、`screeningId`（正 Long）、`orderNo`（精确匹配）。非法或未知筛选字段建议参数错误，不开放客户端指定归属用户。固定 `create_time DESC,id DESC`，返回 `data.items/page/pageSize/total`。无匹配返回空列表、total 0，越界页不报不存在。不承诺并发写期间 count 与列表为跨语句一致快照。

`status=PENDING_PAYMENT` 不含已到期的持久化 `WAIT_PAY`；`status=EXPIRED` 含真正过期和派生过期。筛选、计数与条目转换使用同一请求时间。

### 4.3 `GET /orders/{orderId}`

无 Body、无 Query，建议返回 OrderDetail。正 Long 路径 ID；不存在和不属于本人统一 `404 RESOURCE_NOT_FOUND`。不提供跨用户管理员豁免，也不因支付或退款子接口存在而推定该订单详情入口已实现。

## 5. 取消 `POST /orders/{orderId}/cancel`

建议无 Body、无 Query、不要求 `Idempotency-Key`；幂等依据是**当前订单状态**，不是首次取消响应快照。归属先验证；建议成功返回 `200 {"data":{"orderId":"9001","status":"CANCELLED"}}`。

| 当前持久化状态 | 已确认处理 | 建议 HTTP / code |
|---|---|---|
| `WAIT_PAY`，`now >= deadline` | 先过期并释放本订单仍持有的座位，实际提交；即便有 `PENDING` 也过期，不写取消 | 409 / `RESOURCE_CONFLICT`，安全文案“订单已过期” |
| `WAIT_PAY`，未到期且存在结果未知的 `PENDING` 支付 | 不允许主动取消 | 409 / `PAYMENT_RESULT_UNKNOWN` |
| `WAIT_PAY`，未到期且符合取消条件 | 同事务进入 `CANCELLED` 并释放本订单占座；取消本身不产生退款 | 200 / 成功 |
| `CANCELLED` | 返回取消成功，不重复释放 | 200 / 成功 |
| `EXPIRED` | 告知已过期，不改为取消 | 409 / `RESOURCE_CONFLICT` |
| `PAID/REFUNDING/REFUNDED` | 不允许取消 | 409 / `RESOURCE_CONFLICT` |

取消、支付与过期必须互斥重读。**先判断截止，再检查 PENDING 等主动取消条件**。过期业务结果必须实际提交，不能因随后返回 409 抛业务异常而整体回滚；这里只规定提交边界，不指定锁/事务实现方案。

状态幂等不承诺永久重放取消成功：取消后迟到收款推进到 `REFUNDING/REFUNDED`，再次取消按当时状态返回冲突。任何释放必须校验座位仍由本订单持有，不能按旧明细无条件释放其他订单已重新占用/售出的座位。

## 6. 支付、退款与取消衔接（已确认）

`PENDING` 期间禁止主动取消和新用户退款申请，但**不阻止按原期限过期**。V1 无退款待审核状态，无人工审核；`PAID` 且所有票券未核销并通过其他校验，才可受理整单退款。

用户退票截止为开场前 15 分钟，服务端受理校验时须 `now < cutoff`，恰好截止也不行；截止只限制新申请，不要求已受理退款在截止前完成。取消或过期后的迟到收款补偿不受此限制，不需用户申请或人工审核。

`CANCELLED` 和 `EXPIRED` 收到确认的迟到成功后，保留成功支付及取消/过期历史，自动 `→ REFUNDING → REFUNDED`；退款失败时订单仍 `REFUNDING`，仅退款记录 `FAILED` 供管理员重试。**不恢复待支付/已支付、不出票、不重新占座，也不得在补偿时释放他人座位**。退款记录已建立不等于钱已退回；管理员只能重试明确失败或核查未知结果，不能手工标成功。`REFUNDED` 为终态，`CANCELLED` 只有上述资金补偿例外。

## 7. 错误方案（建议默认，不增加业务代码）

沿用现有基础/支付错误码，暂不创造一组未实现的订单专有码。统一结构为 `code/message/data:null/requestId`，含 `X-Request-Id`；前端不解析 message 做业务分支。

| HTTP | code | 场景 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | ID、Body、分页、筛选或键不合法；座位数量/重复输入错误 |
| 401 | `UNAUTHORIZED` | 未登录或登录失效 |
| 404 | `RESOURCE_NOT_FOUND` | 订单不存在/非本人；创建目标场次或座位不存在 |
| 409 | `IDEMPOTENCY_CONFLICT` | 创建同键不同内容 |
| 409 | `RESOURCE_CONFLICT` | 座位被占/售出/禁售、场次当前不可购、已有有效待支付订单、取消状态冲突或已过期 |
| 409 | `PAYMENT_RESULT_UNKNOWN` | 未到期取消遇到 PENDING |
| 422 | `BUSINESS_RULE_VIOLATION` | 座位与指定场次不匹配等格式正确的业务输入不合法 |
| 500 | `INTERNAL_ERROR` | 必要关联、持久化状态或服务端金额一致性异常；不暴露内部信息 |

已到期取消的 409 是在过期已提交后返回；这项 HTTP/码选择仍是建议默认，状态写入规则已确认。复用 `RESOURCE_CONFLICT` 暂不能让客户端仅按 code 区分所有冲突原因，可提示刷新 GET；是否新增订单专有码是实现前未决技术细节，不改变业务处理。

## 8. 实施前静态基线（历史记录，非最新交付状态）

静态核对当前工作区（含用户已有未提交改动），不是运行时验收：

- `controller/user/OrderController.java`（现名 `controller/user/UserOrderController.java`）只注册订单关联退款两个入口，四订单入口仍 TODO；`OrderService` 为空接口。创建 DTO/订单列表详情 VO、创建快照和取消协议不能称已交付。
- `OrderStatus.getApiCode()` 已将 `WAIT_PAY` 转为 `PENDING_PAYMENT`；`ErrorCode` 已有本规格建议复用的基础码及 `PAYMENT_RESULT_UNKNOWN/IDEMPOTENCY_CONFLICT`，存在枚举不代表四入口已接入。
- 选座 VO 已返回 `screeningId/status/canPurchase/serverTime/seats`，每座含 `screeningSeatId/seatId/rowNumber/seatNumber/status`。下单必须取前者并重新校验物理座位，不把展示 `canPurchase` 当承诺。
- 已有 `OrderExpirationProcessor`、支付实际截止/迟到成功、退款申请截止及自动退款代码；当前支付代码有 `CANCELLED_LATE_COMPENSATION` 审计、按 `order_id + HELD` 条件释放和补偿退款意图。不能将这些局部实现等同于订单创建、取消互斥、同用户同场次限制或整条 E2E 已完成。
- 支付/退款关联 GET 当前按持久化订单状态映射；本规格的订单 GET 派生过期不自动视为这些子接口已经同步，统一查询展示口径须对应实施时确认。
- schema 已有订单总金额、明细单价与通用幂等表；不据此承诺订单幂等清理已实现，不借本规格新增价格或历史快照迁移。

未决：`/users/me/orders` 重复入口保留/别名/移除方案；建议字段、分页、响应与错误码是否最终采纳；订单写入并发/事务和幂等恢复清理的具体实现；与支付/退款子查询展示口径的同步。均不阻塞已确认规则记录，也不授权本次实现或提交。

## 9. 验收清单（逐项实测范围以第 10 节及交付报告为准）

- 1/6 合法边界、0/7/重复拒绝、跨场次与物理停用校验、抢座及全有全无回滚。
- 同用户同场次并发只能一个有效 WAIT_PAY；已到期清理后可新购，不同场次/已支付不误限。
- 创建同键并发、换序重放、异内容冲突、取消/过期后原快照、恰好 24h 及满期新请求。
- 取消逐状态幂等、截止前/恰好/之后、PENDING 与到期优先、409 不回滚过期、与支付/任务竞争。
- CANCELLED/EXPIRED 迟到成功补偿至退款完成、失败保持 REFUNDING、保留历史、不出票、不占座、不释放他人座位。
- 本人/他人/不存在、GET 派生筛选计数且零写库、支付退款查询差异、退票恰好截止拒绝及补偿豁免。

原文档阶段只做文档一致性与 `git diff --check`；以下是后续授权实施记录，不回写历史阶段的验证结论。

## 10. 2026-10-20 实施记录

- 已新增 `OrderWorkflowController`（现名 `UserOrderWorkflowController`）四入口、`CreateOrderRequest/OrderQueryRequest`、详情/摘要/取消 VO、`OrderServiceImpl` 与 `OrderWorkflowMapper`；保留既有 `OrderController`（现名 `UserOrderController`）退款及独立支付入口，不增加 `/users/me/orders` 别名。
- 不新增数据库迁移；复用 `orders/order_item/screening_seat/api_idempotency`。创建快照操作为 `order-create:v1`，同用户创建先持用户行锁，再取幂等行、影厅、场次、订单及升序座位锁；同用户同场次有效待支付名额受事务保护。影厅锁与物理座位维护共用，物理座位和场次可售条件在锁后重查。未改已有支付/退款事务职责。
- 创建业务与成功快照原子提交；快照24小时，换序重放，异内容冲突，取消后仍重放首次响应。到期键在下一次写请求中清除并重新校验；独立有界清理只删除 `order-create:v1` 到期快照，每轮最多500条。新购发现到期旧单时先独立提交过期释放，再重新获取相同顺序的锁、复查幂等与购买条件；新购失败不能撤销旧单过期。
- 取消按当前状态处理，订单行锁与支付/过期共用；先截止再检查 `PENDING`，即时过期在独立事务中提交后抛409，不会随业务错误回滚。仅按当前 `order_id + HELD` 释放，不使用旧明细无条件释放。
- GET 使用同次请求时间派生状态及筛选计数，保持只读、不释放座位；必要关联/金额/状态异常不伪装为正常订单。支付与退款子查询仍按其已公布的持久化状态口径，未擅自变更其契约。
- 真实 MySQL 8.4 隔离库已执行累计Flyway迁移及订单测试；阶段审查验证20项通过、0失败、0错误、0跳过，包含四入口MockMvc→真实Service→MySQL、同键/不同键并发、抢座、共同影厅锁停用先胜、24h边界、取消409提交、派生查询及取消后迟到成功自动退款。HTTP测试使用已认证Principal及请求追踪过滤器，不等同于真实网络Bearer/Redis认证E2E。
- 已执行的一次全量 `clean verify` 在并行影厅测试编译阶段失败，测试未启动；随后一次定向运行也因当时影厅生产源码编译阻塞。恢复会话后生产编译已恢复，新增“新购失败仍保留旧单过期释放”真实Service回归先红并修复；后续一轮21项中20通过、HTTP一项因本机JDBC建连BindException返回503失败，测试夹具已改为有界连接池复验。最终精确统计见 `outputs/order-implementation-report.md`，不将阶段20通过追认为最新全量通过。
- 最终定向续验 `.scratch/order-resumed-pooled.log`：BUILD SUCCESS，21通过、0失败、0错误、0跳过；MySQL8.4隔离新库累计13个Flyway迁移成功，包含最新审查修复及新购失败独立提交回归。测试池最多6条连接，测试后关闭；不清理或删除业务数据库。
- 未覆盖的竞争方向、支付成功/任务/取消全部交错及压力规模，不因上述局部通过而宣称全部验收。
