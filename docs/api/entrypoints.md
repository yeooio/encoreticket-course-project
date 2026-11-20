# 业务入口接口目录

本文档记录当前确定的 HTTP 入口、所属业务和调用角色。所有接口共用的请求头、请求体、响应结构、错误码和字段格式见[《API 通用请求与响应契约》](common-contract.md)；具体业务接口的参数和实现细节以后续接口规格和代码设计为准。

## 认证与用户

| 方法 | 路径 | 调用角色 | 用途 |
|---|---|---|---|
| POST | `/auth/login` | 游客 | 用户登录 |
| POST | `/auth/logout` | 无需预先认证 | 本机退出，服务端尽力撤销请求携带的当前令牌 |
| GET | `/auth/me` | 已登录用户 | 获取当前登录用户 |
| POST | `/users/register` | 游客 | 用户注册 |
| GET | `/users/me` | 已登录用户 | 查询当前用户信息 |
| PATCH | `/users/me` | 已登录用户 | 局部修改昵称、头像地址；未传字段保持原值，不修改邮箱、手机号、密码或登录态版本 |
| PUT | `/users/me/password` | 已登录用户 | 修改当前用户密码 |
| GET | `/users/me/orders` | 已登录用户 | 历史目录中的本人订单查询；与 `/orders` 重复入口方案未决，暂不纳入本次四订单入口 |

`/users/me/tickets` 不在本次票券查询交付范围，不作为别名；当前三个入口见下文[票券查询](#票券查询)。

本轮不交付验证码、邮箱/手机号改绑、头像上传及管理员用户管理，不新增对应入口。
认证代码部署必须配套执行 `V28__user_auth_version.sql`，所有承接请求的实例统一启用数据库状态/版本校验；无版本旧登录态需重新登录，不清空整个 Redis，不延长原 TTL。

### `POST /auth/login`

登录只接受 `username` 和 `password`。用户名按注册规则规范化为小写，成功响应为
`200 OK`，返回 `LoginVO`（不透明访问令牌、用户资料和角色快照）。登录不要求
`Idempotency-Key`，每次成功请求都会建立新的登录态。

用户名不存在或密码错误统一返回 `401 Unauthorized`，错误码为 `UNAUTHORIZED`，
消息为“用户名或密码错误”，不透露用户是否存在。密码正确但用户已被禁用时返回
`403 Forbidden` 和 `FORBIDDEN`。请求格式不合法返回 `400 Bad Request` 和
`INVALID_ARGUMENT`。

### `PUT /users/me/password`

保留数据库事务；普通读取当前 User，校验旧密码与新密码规则后编码，使用读取时的
`authVersion`，在同一 SQL 按用户主键及版本条件更新密码并递增版本。影响行数不是 1
返回 `409 RESOURCE_CONFLICT`（资源已存在或状态发生冲突），不覆盖并发成功的密码，
不自动重试，也不撤销登录态。更新成功才撤销该用户全部 Redis 登录态；撤销故障沿用
`503 SERVICE_UNAVAILABLE` 并回滚密码及版本的契约。

登录不与改密严格串行；并发登录可能返回立即失效的旧版本令牌，认证过滤器通过数据库
版本校验拒绝其后续受保护访问。已通过认证的在途请求不承诺强制中断。

## 电影与场次查询

| 方法 | 路径 | 调用角色 | 用途 |
|---|---|---|---|
| GET | `/movies` | 游客/用户 | 查询电影列表 |
| GET | `/movies/{movieId}` | 游客/用户 | 查询电影详情 |
| GET | `/movies/{movieId}/screenings` | 游客/用户 | 查询电影对应的场次 |
| GET | `/screenings` | 游客/用户 | 查询场次列表 |
| GET | `/screenings/{screeningId}` | 游客/用户 | 查询场次详情 |
| GET | `/screenings/{screeningId}/seats` | 游客/用户 | 查询场次座位及销售状态 |

普通用户只能查询场次和座位，不能直接修改销售状态。

## 管理员电影管理

调用角色：`ADMIN`

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/admin/movies` | 创建电影 |
| GET | `/admin/movies` | 查询电影管理列表 |
| GET | `/admin/movies/{movieId}` | 查询电影管理详情 |
| PUT | `/admin/movies/{movieId}` | 修改未开售电影 |
| POST | `/admin/movies/{movieId}/publish` | 发布电影 |
| POST | `/admin/movies/{movieId}/offline` | 下架电影 |

### 手动发布电影

`POST /admin/movies/{movieId}/publish` 仅允许 `ADMIN`，请求体为 `{"version": 0}`，
其中 `version` 必填，使用管理详情查询得到的当前版本；不要求 `Idempotency-Key`。
允许早于 `publishAt` 立即将 `OFFLINE` 电影上架，也允许发布没有自动发布计划的电影。
成功沿用下架接口返回 `204 No Content`（无响应体），清空 `publishAt`、递增 `version` 并更新修改时间。
原有定时自动发布继续保留，不修改电影现实上映日期、片长或关联场次。

- 未登录：`401 UNAUTHORIZED`；非管理员：`403 FORBIDDEN`。
- 缺少或为空的版本、非法电影 ID：`400 INVALID_ARGUMENT`；电影不存在：`404 RESOURCE_NOT_FOUND`。
- 读取时已上架：`409 MOVIE_ALREADY_RELEASED`，默认消息“电影已经上架”；优先于版本冲突判断，旧版本重复请求也不重放成功，不再次递增版本。
- 读取时仍下架但请求版本不匹配，或读取后写入前被自动发布/其他写操作抢先更新：`409 MOVIE_VERSION_CONFLICT`。

自动发布与手动发布共享电影版本：自动发布仅更新到期的 `OFFLINE` 记录并递增版本；
手动发布按读到的版本执行条件更新。自动发布先于手动读取时返回已上架冲突，
在手动读取后抢先写入时返回版本冲突；手动发布先完成时定时任务跳过该电影。
前端收到冲突或超时应刷新管理详情确认状态，不自动重试。

## 管理员影厅与座位管理

调用角色：`ADMIN`

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/admin/halls` | 查询影厅列表 |
| POST | `/admin/halls` | 创建影厅 |
| GET | `/admin/halls/{hallId}` | 查询影厅详情 |
| PUT | `/admin/halls/{hallId}` | 修改影厅信息 |
| POST | `/admin/halls/{hallId}/enable` | 启用影厅 |
| POST | `/admin/halls/{hallId}/disable` | 停用影厅 |
| GET | `/admin/halls/{hallId}/seats` | 查询影厅物理座位布局 |
| POST | `/admin/halls/{hallId}/seats` | 创建物理座位 |
| POST | `/admin/halls/{hallId}/seats/{seatId}/enable` | 启用物理座位 |
| POST | `/admin/halls/{hallId}/seats/{seatId}/disable` | 停用物理座位 |

接口字段、版本与资格、共同 Hall 锁及当前工作区订单已接核心锁但真实竞争未验收的限制见[影厅与物理座位接口](halls.md)。

## 管理员场次管理

调用角色：`ADMIN`

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/admin/screenings` | 创建电影场次 |
| GET | `/admin/screenings` | 查询场次管理列表 |
| GET | `/admin/screenings/{screeningId}` | 查询场次管理详情 |
| PUT | `/admin/screenings/{screeningId}` | 修改未开售场次 |
| POST | `/admin/screenings/{screeningId}/on-sale` | 开始销售场次 |
| POST | `/admin/screenings/{screeningId}/off-sale` | 停止销售场次 |
| POST | `/admin/screenings/{screeningId}/close` | 关闭场次 |

## 订单

调用角色：已登录用户，仅本人；本节不定义管理员跨用户查询入口。

正式规格见[本人订单接口](orders.md)。以下四入口已由 `UserOrderWorkflowController` 接入 `OrderService`；保留既有退款及支付子入口。字段、分页、HTTP 和错误码采用用户在实施授权中批准的规格建议默认；实测范围见订单规格第10节。`/users/me/orders` 的重复入口问题未决，不删除目录、不新增别名。

| 方法 | 路径 | 调用角色 | 用途 |
|---|---|---|---|
| POST | `/orders` | 已登录用户 | 创建订单并占用场次座位 |
| GET | `/orders` | 已登录用户 | 查询当前用户订单列表 |
| GET | `/orders/{orderId}` | 订单所属用户 | 查询订单详情 |
| POST | `/orders/{orderId}/cancel` | 订单所属用户 | 取消待支付订单 |

订单创建、座位占用、订单超时释放等属于订单业务内部动作，不单独开放为普通用户接口。

## 支付

用户侧的支付创建和查询属于订单关联入口；`AdminPaymentController` 只承载管理员或测试环境的支付异常处理入口。支付成功后的订单状态、场次座位售出和票券生成由 `PaymentService` 在业务事务中协调，不作为独立的普通用户入口。

| 方法 | 路径 | 调用角色 | 用途 |
|---|---|---|---|
| POST | `/orders/{orderId}/payments` | 订单所属用户 | 创建订单支付记录 |
| GET | `/orders/{orderId}/payments` | 订单所属用户 | 查询订单支付记录和支付状态 |
| GET | `/admin/payments` | ADMIN | 分页查询支付记录 |
| GET | `/admin/payments/{paymentId}` | ADMIN | 查询支付、订单、退款、审计与通知摘要 |
| POST | `/admin/payments/{paymentId}/retry` | ADMIN | 对当前失败支付创建新的支付记录并重试 |

支付成功后的订单状态变更、座位售出和票券生成属于支付业务内部动作。支付写入口要求 `Idempotency-Key`；用户失败重付复用支付记录，管理员重试新建支付记录，PENDING 结果未知时不再次扣款。当前模拟渠道结果通过内部处理入口传递，不开放 mock HTTP 接口。字段、分页、24 小时幂等快照及错误见[支付接口](payments.md)。

## 票券查询

调用角色：已登录用户。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/tickets` | 查询当前用户票券列表 |
| GET | `/tickets/{ticketId}` | 查询票券详情 |
| GET | `/tickets/{ticketId}/status` | 查询票券当前状态 |

上述三个入口已有 Controller、Service 与 Mapper 实现；完整字段、示例及真实限制见[本人票券查询接口](tickets.md)。均需 Bearer 认证，只查询当前用户本人票券，管理员也无跨用户权限；不存在和属于他人的票券统一返回 `404 RESOURCE_NOT_FOUND`。

列表按单张票券分页，默认 `page=1/pageSize=10`、每页最多 50，支持展示状态、电影名称字面前缀和订单号精确筛选，固定场次开始时间及票券 ID 倒序。列表无票券码，详情返回本人完整票券码，状态接口仅返回 `data.status`。

票券持久化状态仍为 `VALID/USED/VOID`；查询展示增加派生的 `EXPIRED/REFUNDING`，GET 不写回状态。技术时间输出 UTC `Z`，场次时间输出 `Asia/Shanghai` 偏移 `+08:00`。本次不新增票券缓存或数据库迁移，文档与实现静态对照不代表真实 MySQL 或 E2E 验证通过。

## 票券核销

### 检票入口

调用角色：`CHECKIN_STAFF`

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/ticket-verifications` | 扫描票券并完成验证与核销 |

该接口使用用户出示的 `ticketCode`，不接收数据库内部 `ticketId` 作为扫码凭证。核销记录、操作人员和设备信息由核销业务处理。

### 核销记录查询

调用角色：具备核销记录查询权限的管理人员，第一版可由 `ADMIN` 使用。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/admin/ticket-verifications` | 查询成功核销记录 |

以下入口不再保留：

```text
POST /admin/tickets/{ticketId}/verify
```

普通管理员不通过票券管理接口直接修改票券状态。

## 退款

用户通过订单入口发起和查询整单退款；`AdminRefundController` 负责管理员处理无法自动完成的退款。退款成功后的订单变更、票券作废和座位释放由 `RefundService` 协调，不由 Controller 直接修改状态。

| 方法 | 路径 | 调用角色 | 用途 |
|---|---|---|---|
| POST | `/orders/{orderId}/refunds` | 订单所属用户 | 申请整单退票 |
| GET | `/orders/{orderId}/refunds` | 订单所属用户 | 查询订单退款记录 |
| GET | `/admin/refunds` | `ADMIN` | 查询退款列表 |
| GET | `/admin/refunds/{refundId}` | `ADMIN` | 查询退款详情 |
| POST | `/admin/refunds/{refundId}/process` | `ADMIN` | 处理退款 |

退款成功后的订单变更、票券作废和座位释放属于退款业务内部动作。已批准本次契约见[退款接口](refunds.md)：全额原路、自动模拟、不可撤回；管理员仅 FAILED 重试或 PENDING 核查，不可手工标记成功。

## 不作为 HTTP 入口的内部动作

以下动作由对应业务服务在事务中完成，不独立开放给普通用户：

- 创建订单时，将场次座位从 `AVAILABLE` 改为 `HELD`；
- 支付成功时，将场次座位从 `HELD` 改为 `SOLD`，并生成票券；
- 取消订单或订单超时时，释放场次座位；
- 退票成功时，将票券改为 `VOID`，并释放场次座位；
- 支付成功通知迟到时，订单由 `EXPIRED` 或 `CANCELLED` 进入 `REFUNDING`，自动创建补偿退款，成功后进入 `REFUNDED`；不恢复可支付状态、不重新占座、不生成票券，保留取消历史且不得释放其他订单的座位。

## 统一横切要求

所有需要登录的入口都需要认证校验；管理、检票和用户入口分别进行权限校验；订单和票券查询执行数据归属校验；创建订单、支付通知、出票、退款和票券核销需要幂等处理。
