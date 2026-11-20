# 管理员场次配置

创建和修改的时间输入必须为带偏移的 RFC3339 字符串（例如 `2026-10-20T20:00:00+08:00` 或等价的 `2026-10-20T12:00:00Z`），不接受无偏移时间。服务端按同一时间点转换为影院 `Asia/Shanghai` 本地业务时间，以 `LocalDateTime` 写入 `DATETIME`；返回时间带 `+08:00`。创建和修改接口只允许管理员调用。

`price` 必填且大于零，最多8位整数、2位小数，范围 `0.01` 至 `99999999.99`，超过范围或精度返回 `400 INVALID_ARGUMENT`，不自动四舍五入。管理详情返回固定两位小数的金额字符串及 `currency: "CNY"`。`saleStartTime` 必须严格早于服务端计算的 `saleEndTime`，相等也返回 `422 BUSINESS_RULE_VIOLATION`。

## POST `/admin/screenings`

`saleMode` 默认为 `SCHEDULED`，创建 `WAIT_SALE` 场次并要求未来的 `saleStartTime`；`IMMEDIATE` 使用锁后当前时间开售，创建 `ON_SALE` 场次，此时不能传 `saleStartTime`。`admissionOpenTime` 省略时默认为 `startTime - 15分钟`；`saleEndTime` 服务端固定为 `startTime - 15分钟`，`endTime` 根据电影片长生成，客户端不能覆盖。`saleMode` 是创建命令选项，不单独持久化。ID 和票价请求字段必须是字符串。

```json
{
  "movieId": "10001",
  "hallId": "10",
  "price": "39.90",
  "startTime": "2026-10-20T20:00:00+08:00",
  "saleStartTime": "2026-10-19T00:00:00+08:00",
  "admissionOpenTime": "2026-10-20T19:40:00+08:00"
}
```

成功返回 `201 Created`、`Location: /admin/screenings/{id}` 及 `ApiResponse<ScreeningCreatedVO>`，响应仅包含 `{"data":{"id":"10001"}}`。创建后通过管理详情 GET 获取 `version`、`status` 和时间，不在创建响应中返回详情；SCHEDULED 初始 WAIT_SALE，IMMEDIATE 初始 ON_SALE。

## PUT `/admin/screenings/{screeningId}`

只有锁后重读的场次仍为 `WAIT_SALE`，且锁后读取的 Clock 时间严格早于原场次 `saleStartTime`，才允许修改；等待锁期间到达开售时刻也拒绝。请求必须携带详情响应中的 `version`；锁后版本不匹配或场次已换厅返回 `409 RESOURCE_CONFLICT`。省略 `admissionOpenTime` 时保留原人工配置，不重新套用默认值；`saleEndTime` 仍由服务端按开场前15分钟计算。

```json
{
  "movieId": "10001",
  "hallId": "11",
  "price": "42.00",
  "startTime": "2026-10-20T20:00:00+08:00",
  "saleStartTime": "2026-10-19T00:00:00+08:00",
  "admissionOpenTime": "2026-10-20T19:35:00+08:00",
  "version": 0
}
```

换厅或改排时，服务端先按影厅 ID 升序锁定全部相关 `hall` 行，再按影厅 ID、场次 ID 升序锁定并重读场次；所有业务校验在获取锁后执行。当前场次与相邻场次必须满足至少15分钟放映周转，且入场开放时间不能与前场放映重叠；场次结束不能晚于后场入场窗口。

## GET `/admin/screenings/{screeningId}`

返回管理详情，包括电影、影厅、价格、放映时间、售票截止、允许入场时间、状态和版本。不存在返回 `404 RESOURCE_NOT_FOUND`。

```json
{
  "data": {
    "id": "10001",
    "movieId": "20001",
    "movieTitle": "示例电影",
    "hallId": "10",
    "hallName": "一号厅",
    "price": "39.90",
    "currency": "CNY",
    "startTime": "2026-10-20T20:00:00+08:00",
    "endTime": "2026-10-20T22:00:00+08:00",
    "saleStartTime": "2026-10-19T00:00:00+08:00",
    "saleEndTime": "2026-10-20T19:45:00+08:00",
    "admissionOpenTime": "2026-10-20T19:40:00+08:00",
    "status": "WAIT_SALE",
    "version": 0
  }
}
```

迁移 `V20__screening_admission_support.sql` 为历史场次补齐 `admission_open_time` 和 `version`。迁移前会检查历史放映时间、售票时间和同影厅周转冲突；预检失败会终止迁移，不自动改排。审计时间仍沿用 UTC 技术时间语义。

## 用户选座与订单输入衔接

现有 `GET /screenings/{screeningId}/seats` 返回 `data.screeningId/status/canPurchase/serverTime/seats`；每项包含 `screeningSeatId/seatId/rowNumber/seatNumber/status`。创建订单使用 `screeningId + screeningSeatIds`，取场次座位 ID 而非物理 `seatId` 或排座编号。选座响应只展示当时状态、不占座；下单须重新验证场次归属、场次座位状态及物理座位可用性，任一失败整单回滚。正式约定见[订单规格](orders.md)，四订单入口尚未实现。

## 合并后的数据库升级

保留 master 已应用的 V3 支付、V20 场次入场、V21 幂等和 V22 支付尝试历史。电影迁移使用 V23/V24；V25 恢复片长、排片片长与清场快照、影厅排片版本及调度索引，保留 V20 人工入场时间和 V21 支付/核销幂等结构。V26 恢复正票价、片长非空、结束时间匹配片长和开场前 15 分钟售票截止约束。

有历史数据时先仅升级到 V25，停写并按 `docs/database/screening-v6-preflight.sql` 检查，依据已核实数据补录电影片长和历史场次快照；清理违规项后再升级 V26。迁移不推测片长、不自动改排。已运行旧电影 V3/V4 的独立分支数据库存在 Flyway 版本含义冲突，不能直接复用此历史链；必须先人工核对其 history/checksum 并制定独立接管方案，不能自动 repair。
