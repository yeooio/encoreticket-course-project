# 本人票券查询接口

本文依据 `.scratch/ticket-query/spec.md` 与当前 `UserTicketController`、DTO、VO、Service、Converter、Mapper 实现记录三个只读 HTTP 入口。共享响应及错误体见[通用契约](common-contract.md)，状态规则见[状态转换矩阵](../domain/state-transitions.md)和[枚举映射](../domain/status-enum-mapping.md)，时间遵循 [ADR 0005](../adr/0005-time-semantics.md)。代码存在不代表已通过真实 MySQL 或端到端验收。

## 1. 入口与权限

| 方法 | 路径 | 成功响应 `data` |
|---|---|---|
| GET | `/tickets` | `PageResponse<TicketSummaryVO>` |
| GET | `/tickets/{ticketId}` | `TicketDetailVO` |
| GET | `/tickets/{ticketId}/status` | `TicketStatusVO`，仅含 `status` |

三个入口均要求 `Authorization: Bearer <accessToken>`，成功返回 `200 OK`；无请求体，不要求 `Idempotency-Key`。成功体为 `{"data": ...}`，响应携带 `X-Request-Id`。用户身份来自服务端认证主体，Service 与 Mapper 以 `orders.user_id` 限制本人范围，不提供客户端 `userId` 参数。当前无额外角色限制，`ADMIN`、`CHECKIN_STAFF` 通过这些入口也只能查询自己的票券。`ticket.read.self` 仅为规格中的前端能力概念，未新增后端权限码或权限表。

`/users/me/tickets` 不属于本次交付，也不作为这三个入口的别名。核销、退款、支付写操作及管理员查询他人票券均不属于这些入口。

## 2. 列表 `GET /tickets`

### 查询参数

| 参数 | 类型 | 默认值 | 校验与语义 |
|---|---|---|---|
| `page` | integer | `1` | 从 1 开始，至少 1，使用 Java Integer 范围 |
| `pageSize` | integer | `10` | 1..50 |
| `status` | string | 不筛选 | 区分大小写：`VALID`、`USED`、`VOID`、`EXPIRED`、`REFUNDING` |
| `movieTitle` | string | 不筛选 | 去首尾空白后最多 255 字符；电影名称字面前缀匹配 |
| `orderNo` | string | 不筛选 | 去首尾空白后最多 32 字符；订单号精确匹配 |

三个字符串筛选先用 `strip()` 去首尾空白，空串或全空白视为未传，多个条件取交集。分页参数缺省采用默认值，空值、非整数、溢出或超出范围返回 400。不提供 `movieId`、`orderId`、`screeningId` 或自定义排序参数。

电影名称中的 `%`、`_`、`!`、反斜杠按普通字符处理，Mapper 使用绑定参数及 `ESCAPE '!'` 转义；不向调用方开放通配符或排序表达式。名称及订单号比较的大小写、重音等仍受 MySQL 列排序规则影响，不承诺逐字节比较。

按单张票券分页，固定排序为 `screening.start_time DESC, ticket.id DESC`。同一订单多张票分别计数。状态过滤在 SQL 分页前执行，count 与 select 使用相同业务过滤条件及请求内同一个 `now`。

### 分页响应

| 字段 | JSON 类型 | 说明 |
|---|---|---|
| `items` | array | 本页票券摘要；无匹配或越过末页时为 `[]` |
| `page` | number | 本次请求页码，不自动回退末页 |
| `pageSize` | number | 本次请求页大小 |
| `total` | number | 符合条件的票券总数，不是订单数 |

`items` 每项字段如下；列表不返回 `ticketCode`。

| 字段 | JSON 类型 | 说明 |
|---|---|---|
| `id` | string | 票券 ID |
| `orderId` | string | 订单 ID |
| `movieId` | string | 电影 ID |
| `screeningId` | string | 场次 ID |
| `orderNo` | string | 订单业务编号 |
| `movieTitle` | string | 电影名称 |
| `hallName` | string | 影厅名称 |
| `screeningStartTime` | string | 场次开始时间，`+08:00` |
| `screeningEndTime` | string | 场次结束时间，`+08:00` |
| `rowNumber` | number | 座位排号 |
| `seatNumber` | number | 排内座位号 |
| `status` | string | 五种票券展示状态之一 |
| `verifiedAt` | string / null | 成功核销技术时间，UTC `Z`；无核销记录为 null |

示例（示意数据，非实库执行结果）：

```http
GET /tickets?page=1&pageSize=10&status=VALID
Authorization: Bearer <accessToken>
Accept: application/json
```

```json
{
  "data": {
    "items": [
      {
        "id": "90001",
        "orderId": "80001",
        "movieId": "10001",
        "screeningId": "20001",
        "orderNo": "ORD-80001",
        "movieTitle": "示例电影",
        "hallName": "一号厅",
        "screeningStartTime": "2026-10-20T19:00:00+08:00",
        "screeningEndTime": "2026-10-20T21:00:00+08:00",
        "rowNumber": 3,
        "seatNumber": 8,
        "status": "VALID",
        "verifiedAt": null
      }
    ],
    "page": 1,
    "pageSize": 10,
    "total": 1
  }
}
```

## 3. 详情 `GET /tickets/{ticketId}`

`ticketId` 必须匹配 `[1-9][0-9]*`，且在 Long 正数范围内（最大 `9223372036854775807`）。不接受零、前导零、正负号、空白、小数、指数、非数字或溢出。不存在和属于他人的票券均返回相同的 `404 RESOURCE_NOT_FOUND`。

| 字段 | JSON 类型 | 说明 |
|---|---|---|
| `id` | string | 票券 ID |
| `ticketCode` | string | 本人完整票券码，仅详情返回 |
| `status` | string | 票券展示状态 |
| `orderId` | string | 订单 ID |
| `orderNo` | string | 订单业务编号 |
| `orderStatus` | string | 订单状态：`PENDING_PAYMENT`、`PAID`、`CANCELLED`、`EXPIRED`、`REFUNDING`、`REFUNDED`；与票券展示状态是不同字段 |
| `movieId` | string | 电影 ID |
| `movieTitle` | string | 电影名称 |
| `screeningId` | string | 场次 ID |
| `hallId` | string | 影厅 ID |
| `hallName` | string | 影厅名称 |
| `startTime` | string | 场次开始时间，`+08:00`；字段名与列表不同 |
| `endTime` | string | 场次结束时间，`+08:00` |
| `rowNumber` | number | 座位排号 |
| `seatNumber` | number | 排内座位号 |
| `unitPrice` | string | `order_item.unit_price` 购票价格快照，以 `BigDecimal.toPlainString()` 输出十进制字符串，不是当前场次售价 |
| `currency` | string | 固定 `CNY` |
| `verifiedAt` | string / null | 成功核销时间，UTC `Z`；未核销为 null |
| `createdAt` | string | 票券创建技术时间，UTC `Z` |

所有 BIGINT 标识均以字符串返回。除 `verifiedAt` 外，以上必要字段缺失视为内部数据异常，不拼装虚假详情。当前完整性校验不要求 `USED` 必须存在核销记录，因此损坏数据中也可能出现 `USED` 且 `verifiedAt=null`，不能把查询校验理解为完整业务审计。

本人详情不额外脱敏或二次认证；票券码不得放入请求 URL、日志或前端持久化。`TicketDetailVO.toString()` 不包含完整票券码，但这不等于已实现全链路日志脱敏审计。

## 4. 状态 `GET /tickets/{ticketId}/status`

ID 校验和本人归属规则与详情一致。成功响应仅包含展示状态，不返回 ID、订单、票券码或核销时间：

```json
{"data":{"status":"REFUNDING"}}
```

当前实现仍读取并校验完整关联投影，不是独立的单列 SQL 查询；必要关联或价格异常同样可能返回 500。

## 5. 展示状态与时间

展示规则按下表顺序执行，先验证必要关联及合法持久化状态；终态优先不豁免数据完整性检查。

| 顺序 | 条件 | 展示状态 | 建议文案 |
|---|---|---|---|
| 1 | `ticket.status=USED(1)` | `USED` | 已核销 |
| 2 | `ticket.status=VOID(2)` | `VOID` | 已作废 |
| 3 | `ticket.status=VALID(0)` 且订单 `REFUNDING(4)` | `REFUNDING` | 退款处理中 |
| 4 | `ticket.status=VALID(0)` 且订单 `PAID(1)`，`endTime <= now` | `EXPIRED` | 已过场次 |
| 5 | `ticket.status=VALID(0)` 且订单 `PAID(1)`，`endTime > now` | `VALID` | 有效 |

`ticket.status` 只持久化 `0/1/2`；`EXPIRED`、`REFUNDING` 是 `TicketDisplayStatus` 查询派生值，无数据库编码，也不写回票券表。到达结束时间（含相等）即展示过期，不依赖 `screening.status=ENDED` 或调度执行。退款中优先于过期；退款失败后订单仍为 `REFUNDING`，不会因查询自动恢复有效；退款成功并将票券作废后展示 `VOID`。

有效票关联非 `PAID/REFUNDING` 订单（例如 `REFUNDED+VALID`）、未知编码和已确认本人资源的必要关联缺失按 500 处理。历史 `USED/VOID` 不要求订单当前仍为 `PAID`，也不因电影下架、影厅停用或场次停售、关闭、结束而过滤掉历史票券。展示 `VALID` 不授予核销权限；核销必须由独立写业务检查订单、票券、场次与入场条件。

Service 每个请求捕获一次注入的 `java.time.Clock` 时间，并转换为 `Asia/Shanghai` 的 `LocalDateTime`，供 SQL 和 Java 使用同一 `now`。场次业务时间按该时区输出 RFC 3339 字符串，例如 `2026-10-20T19:00:00+08:00`；`verifiedAt/createdAt` 使用 `Instant.toString()` 输出 UTC，例如 `2026-10-19T03:00:00Z`。不依赖部署机器默认时区，技术时间不会统一改为 `+08:00`。

## 6. 错误响应

| HTTP | `code` | 当前查询触发条件 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | 分页类型、范围、长度、状态枚举或路径 ID 非法 |
| 401 | `UNAUTHORIZED` | 未登录，令牌缺失或失效 |
| 403 | `FORBIDDEN` | 共享权限拒绝分类；当前三个查询没有额外角色门槛，不将跨用户访问返回为 403 |
| 404 | `RESOURCE_NOT_FOUND` | 详情或状态票券不存在、属于他人，或断链后无法证明归属 |
| 500 | `INTERNAL_ERROR` | 必要关联缺失、未知状态、有效票与订单不一致、价格异常、数据库或未预期错误 |

`409 RESOURCE_CONFLICT`、`422 BUSINESS_RULE_VIOLATION` 是共享写业务错误分类，当前查询无常规业务触发条件。没有新增票券专用限流或 `429` 契约。错误使用真实 HTTP 状态及统一响应体，不返回 SQL、内部异常消息或票券码：

```json
{
  "code": "RESOURCE_NOT_FOUND",
  "message": "请求的资源不存在",
  "data": null,
  "requestId": "req_example"
}
```

## 7. 当前限制与验证边界

- 无新增票券业务缓存、数据库字段、持久化状态、依赖、索引或迁移；沿用现有 Redis 登录认证。工作区其他功能的迁移不属于票券查询结构变更。
- 列表通常执行完整性预检查、count、分页 select 三条 SQL，通过关联查询避免逐票 N+1。当前 `hasInvalidOwned` 只按本人范围检查，不应用电影、订单号、状态或分页筛选；本人其他票券命中其异常谓词也会使整次列表返回 500。价格及更多必要字段由选中投影再校验，预检查并非全量数据审计。
- `ticket → order_item → orders` 断链导致无法证明归属时，该记录不进入本人列表，单票查询返回 404；归属已成立后的必要关联缺失按 500 处理。不宣称能识别全部孤儿记录。
- 查询无写事务、行锁和写副作用。多语句在并发支付、退款或核销时可能观察到相邻时刻的已提交数据，`total/items` 不保证同一数据库快照；同一个 `now` 仅统一时间判定，也不提供跨请求分页快照。
- 除价格快照外，电影、影厅、场次、座位信息来自当前关联记录，不是全量历史快照。复用已有唯一约束与索引不代表已验证大数据量查询性能；真实 MySQL 转义行为、执行计划、深分页性能仍须在隔离库验证。
- 未付款时通常只有订单和明细，查询不创建票券。退款受理与核销互斥、整单退款检查所有票券属于对应写业务，不能由只读接口推断并发保障已验收。
- 无前端工程交付，不包含页面、URL 状态同步、移动端、统一限流或前端 E2E。本次仅做文档与代码静态对照及文档差异检查，未运行编译、单元测试、真实 MySQL、EXPLAIN 或 E2E，不据测试文件存在宣称通过。支付出票至退款/核销的完整链路需要另行集成验收。
