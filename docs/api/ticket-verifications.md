# 票券核销与成功审计查询

遵循 `common-contract.md` 的 `ApiResponse`、`PageResponse`、错误体及 `X-Request-Id`。本规格补充旧扫码示例：必须同时提交 `screeningId` 和 `Idempotency-Key`。

## POST /ticket-verifications

仅 `CHECKIN_STAFF`；只有 `ADMIN` 不能核销。认证沿用登录态角色快照，操作员来自可信认证主体，不接受客户端指定。

请求头：`Authorization: Bearer <token>`、`Content-Type: application/json`、单个 `Idempotency-Key`。幂等键为 1–128 位 `[A-Za-z0-9_-]`，大小写敏感。无 Query 参数。

```json
{"ticketCode":"AbC_123","screeningId":"10001"}
```

只接受这两个字符串字段。`ticketCode` 非空白、最长128字符，原样保留、不 trim、不折叠大小写；SQL 使用二进制精确比较。`screeningId` 为无前导零的正整数十进制字符串，最大 `9223372036854775807`。拒绝数字类型、未知字段以及伪造的 `operatorId`、`deviceCode`、`status`、`verifiedAt`。

200 响应示例：

```json
{
  "data": {
    "verificationId": "20001",
    "ticketId": "30001",
    "screeningId": "10001",
    "movieTitle": "示例电影",
    "startTime": "2026-10-19T20:15:00+08:00",
    "endTime": "2026-10-19T22:00:00+08:00",
    "hallName": "1号厅",
    "rowNumber": 3,
    "seatNumber": 8,
    "status": "USED",
    "verifiedAt": "2026-10-19T12:00:00Z"
  }
}
```

场次时间及 `admission_open_time` 是 `Asia/Shanghai` 本地时间；核销与幂等到期时间是 UTC 技术时间。核销审计沿用 DATETIME 秒精度。电影下架和影厅目录停用不直接使票券失效。

业务锁顺序为 screening → orders → ticket；锁后重新读取关联、状态和当前时间。票券必须 VALID、订单必须 PAID；REFUNDING（含退款失败仍退款中）拒绝。ON_SALE/OFF_SALE 可在 `[admissionOpenTime,endTime)` 入场；WAIT_SALE/CLOSED/ENDED 拒绝。不改订单整体状态或 SOLD 座位。

同一事务完成幂等占位、VALID→USED、审计插入和成功快照保存。操作名 `ticket-verification:v1`，唯一作用域 `operator_id + operation + idempotency_key`；操作名与键采用 `ascii_bin` 排序，摘要 SHA-256 包含保留大小写的票码和规范场次 ID，不保存原始请求或认证令牌。

成功结果保留24小时；认证、操作员存在性和基础参数校验后，同人同操作同键同内容直接返回首次成功快照，不重新检查当前场次时间/状态。它仅表达历史成功，不代表再次放行。同键异参409；新键扫已核销票只返回“票券已核销”，不返回首次操作员、设备、订单号、购票人信息。唯一键竞争失败的事务先完整回滚，再使用 REQUIRES_NEW 事务读取已提交结果。过期记录替换和清理均与行锁协调。

## GET /admin/ticket-verifications

仅 ADMIN，只读。不提供审计修改、删除、批量、导入导出入口。无 Body；拒绝未知或重复 Query 参数。

| Query | 规则 |
|---|---|
| page | 默认1，1至Integer最大值 |
| pageSize | 默认20，1–100 |
| screeningId / operatorId | 可选，规范正整数 ID 字符串；合法不存在返回空页 |
| ticketCode | 可选，最长128，大小写敏感精确匹配 |
| deviceCode | 可选，最长64，大小写敏感精确匹配；本期服务端固定DEFAULT |
| verifiedAtFrom / verifiedAtTo | 可选，带偏移RFC3339，区间[from,to)，双边存在须from<to |

固定 `verified_at DESC,id DESC`，不接受客户端排序 SQL。响应为 `data: {items: [], page: 1, pageSize: 20, total: 0}`。列表项在扫码成功字段基础上增加 `orderNo`、`operatorId`、`deviceCode`，不含联系方式。Mapper 执行一次 COUNT 和一次带 LIMIT/OFFSET 的联表投影，避免 N+1。审计作为查询根表，必要关联缺失报告500，不静默丢失审计或拼装成功数据。

## 错误与日志

| HTTP | code | 条件 |
|---|---|---|
| 400 | INVALID_ARGUMENT | 缺头、类型/范围错误、未知字段、非法分页/时间范围 |
| 401 | UNAUTHORIZED | 未登录或登录态失效 |
| 403 | FORBIDDEN | 缺少对应角色 |
| 404 | RESOURCE_NOT_FOUND | 票码精确匹配不存在或所选场次不存在 |
| 409 | TICKET_ALREADY_USED / TICKET_VOID | 已核销/作废 |
| 409 | ORDER_NOT_ADMISSIBLE / SCREENING_STATUS_CONFLICT | 订单/场次状态不允许 |
| 409 | IDEMPOTENCY_CONFLICT | 同作用域键不同内容 |
| 422 | TICKET_SCREENING_MISMATCH | 错场 |
| 422 | ADMISSION_NOT_OPEN / ADMISSION_ENDED | 开放前/结束时刻及之后 |
| 500 | INTERNAL_ERROR | 必要关联缺失、异常状态、审计/快照写入失败等 |

失败只写脱敏应用日志，不写失败业务审计。不记录票码、幂等键、认证令牌、请求体或 SQL 异常消息；日志可通过 requestId 关联。成功记录固定 `device_code=DEFAULT`。不将任意唯一约束异常解释成票券已核销。

## 清理与部署

按 Flyway 版本顺序应用 `V20__screening_admission_support.sql` 和 `V21__ticket_verification_idempotency.sql`，分别提供场次入场配置与幂等表。`docs/database/schema.sql` 已同步最终结构；已有数据库通过版本迁移升级。每分钟单事务最多删除500条本操作的到期记录，不删除成功审计及其他操作记录；延时可通过 `encore.ticket-verification.cleanup-delay-ms` 配置。

退款受理方必须遵守共同 orders 行锁协议：锁住订单后检查整单票券（多票按ID升序），发现 USED 拒绝，再推进 REFUNDING。退款契约见 refunds.md；退款与核销共同遵守 screening → orders → ticket 锁序。并发及端到端验收以实际测试报告为准。

## 测试

```bash
mvn -B -ntp -Dtest=TicketVerificationTransactionTest,TicketVerificationServiceTest,TicketVerificationRulesTest,TicketVerificationControllerTest,TicketVerificationMySqlTest test
```

真实 MySQL 测试显式启用，必须使用预先创建的空隔离库 `encore_ticket_verification_test_*`。密码仅通过 `TICKET_VERIFICATION_MYSQL_PASSWORD` 环境变量提供；测试拒绝非隔离库和非空库。测试将生产 V1/V2/V20/V21 原文复制到临时迁移位置并通过 Flyway 执行，排除 V3 等其他业务迁移；每项测试前仅在已确认初始为空的专属隔离库执行 Flyway clean，不删除数据库。覆盖空库迁移、从 V2 存量升级的入场时间与 version 回填、约束及历史排片预检失败；连接不可用时明确跳过。

```bash
mvn -B -ntp -Dtest=TicketVerificationMySqlTest -Dticket.verification.mysql.enabled=true '-Dticket.verification.mysql.url=jdbc:mysql://127.0.0.1:3308/encore_ticket_verification_test_local?connectionTimeZone=UTC' -Dticket.verification.mysql.user=encore_ticket test
```

覆盖同键并发、不同键争票、票码及幂等作用域大小写、快照冲突、审计/快照故障回滚、到期清理保留审计、异常关联及退款订单锁竞争协议。真实数据库不可用时默认跳过，不将 mock 事务断言当作 MySQL 隔离与锁行为证明。

### 本次实际验证（2026-10-19）

- 主代理已运行 `mvn clean verify`：181项，0失败，17跳过（主代理提供的全量验证结果）。此前并行开发期间的编译与测试阻塞已不再作为当前结论。
- 本次收尾运行上面的核销定向 Maven test 命令：29项，17通过，0失败、0错误、12跳过。HTTP 5项、Service 2项、事务8项、Rules 2项通过；MySQL 12项（含新增空库、存量回填、失败预检3项）因未启用数据库开关跳过。生产及测试源码均通过常规 Maven 编译。
- 本次探测127.0.0.1:3308仍不可达；真实MySQL迁移、锁竞争、故障回滚和EXPLAIN未执行，不声称数据库集成通过。
