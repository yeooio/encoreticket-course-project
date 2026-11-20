# Encore Ticket Backend

面向单影院场景的电影票务后端，基于 Java 21 和 Spring Boot 构建，覆盖电影与排片管理、选座下单、模拟支付、出票、入场核销及整单退款。

项目重点不只是增删改查，而是交易过程中的一致性：同一座位如何避免重复售出、超时重试如何避免重复业务效果、迟到收款如何补偿，以及退款与核销竞争时如何保护票券权益。

> 本仓库只包含后端，不包含前端应用。支付与退款使用模拟渠道，不对接真实资金。当前版本不包含多影院、优惠定价或部分退款，不承诺生产部署可用性。

## 核心业务

| 模块 | 已实现能力 |
| --- | --- |
| 认证与用户 | 注册、登录、退出、本人资料、修改密码、不透明 Bearer 令牌与登录态版本校验 |
| 电影目录 | 公开查询、管理维护、上下架、计划发布、关联订单后的资料冻结 |
| 影厅与场次 | 物理座位维护、独立编辑版本、排片冲突检查、场次席位快照、售票及入场时间控制 |
| 订单 | 每单同场次 1～6 个不同座位、原子占座、创建幂等、本人查询、取消与过期释放 |
| 支付与票券 | 支付尝试、结果通知去重、成功结算与出票、本人票券查询、迟到收款补偿 |
| 核销与退款 | 检票角色授权、票码核销及审计、整单退款、退款结果事件与失败恢复 |

正常购票链路：

```text
查询电影 / 场次 / 座位
    → 创建订单（WAIT_PAY，席位 HELD）
    → 发起模拟支付 → 成功通知
    → 事务结算（订单 PAID，席位 SOLD，每个明细生成一张票）
    → 本人查票 → 员工核销（票券 USED，保存成功审计）
```

异常与退款链路：

```text
待支付订单 → 取消 / 到期 → 释放本单仍占用的席位
    → 此后收到成功收款 → 自动创建补偿退款，不重新占座或出票

已支付订单 → 校验整单退款资格 → REFUNDING
    → 外部模拟结果事件 → 退款 SUCCESS、订单 REFUNDED、原票券 VOID
```

以上为领域状态，例如持久化 `WAIT_PAY` 在订单接口中映射为 `PENDING_PAYMENT`。完整流转及边界见[状态规则](docs/domain/state-transitions.md)。

## 关键设计

### 座位竞争与事务一致性

- 区分物理座位 `Seat` 与场次销售席位 `Screening seat`，维护物理座位不直接等于修改销售库存。
- 使用 MySQL 行锁、条件更新及数据库约束保护占座和结算，不依赖 Redis 分布式锁保证库存正确性。
- 订单、明细与全部席位占用在事务内全有全无；结算同时检查归属、售座和出票，避免部分成功。
- Service 管理业务资格及事务边界；必要的多步锁定、完整性检查与模型装配由 Repository 封装，SQL 保留在 Mapper XML。

### 请求幂等与通知去重

- 订单创建、支付、退款及核销按各自契约处理幂等；操作者、操作、键及内容共同决定合法重放。
- 同键同内容重放约定的首次响应快照，同键异内容返回冲突；历史快照不代表资源当前仍有操作资格。
- 渠道结果以事件身份和内容摘要去重，重复、乱序通知不能重复出票或倒退已确认成功状态。
- 电影及场次等管理写接口不统一承诺请求重放，不能将业务唯一约束误认为所有 POST 都可以自动重试。

### 时间边界与迟到补偿

- 新购售票截止为开场前 15 分钟；旧订单付款截止为 `min(createdAt + 15分钟, expires_at)`，达到截止即到期。
- 自然停售或管理员提前停售不缩短已有订单的付款窗口；主动退款截止独立计算，不随付款窗口延长。
- 取消或过期后的成功收款进入补偿退款，不恢复购票权益，也不释放其他购买方持有的席位。
- 定时任务用于推进状态，业务入口仍检查实时资格，不依赖调度准点执行。

### 退款恢复与核销互斥

- 退款申请先持久化 `PENDING` 和尝试记录，等待外部模拟结果事件；未知结果不因超时直接判失败。
- 传输重试保留原事件身份；仅明确失败后的业务重试创建新尝试，不把网络重投当作新资金操作。
- 结果确认、退款/订单状态、原票券权益与审计在同一事务提交，失败后可重投原事件。
- 退款与核销通过共同业务锁及资格检查协调，避免同一权益既完成退款又成功入场。

### 认证与权限边界

- 使用随机不透明令牌而非 JWT，Redis 以令牌摘要作为键；登录态固定有效期，不随请求续期。
- 认证同时校验数据库账号状态与登录态版本；改密通过版本条件更新和会话撤销使旧凭据失效。
- `ADMIN`、`CHECKIN_STAFF`、`USER` 职责分离；管理员不自动获得检票权限。
- 本人订单、支付、退款及票券在服务端检查归属，Controller 分包和前端隐藏按钮都不替代授权。
- 公共错误保留 HTTP 状态区别和 `requestId`，不将所有业务错误包装成 HTTP 200。

设计取舍与适用边界见 [ADR](docs/adr/) 和 [API 通用契约](docs/api/common-contract.md)。

## 技术栈与架构

| 技术 | 用途 |
| --- | --- |
| Java 21 / Spring Boot 4.1.1 | 应用运行与依赖集成 |
| Spring MVC / Bean Validation | REST API、请求校验和统一异常处理 |
| Spring Security / Redis | 身份认证、角色授权、登录态与撤销索引 |
| MyBatis-Plus 3.5.17 / Mapper XML | 标准 CRUD、自定义查询、条件更新与行锁 |
| MySQL 8.4 / Flyway | 交易持久化、约束与版本化迁移 |
| JUnit Jupiter / Mockito / Spring Boot Test | 单元、接口、安全及真实依赖测试 |
| Python / JMeter | 模拟事件脚本、验证工具及有限性能实验 |

```text
HTTP 请求 → Spring Security → Controller → Service → Mapper → MySQL
                                              └→ Repository → Mapper
                   └→ Redis 登录态
```

```text
src/main/java/io/github/chenforegone/encoreticket/
├── controller/user/   # User 前缀：公开查询、本人资源与认证入口
├── controller/admin/  # Admin 前缀：管理及独立检票授权入口
├── service/           # 业务决策、归属校验与事务编排
├── repository/        # 必要的持久化编排，不强制每个模块创建
├── mapper/            # MyBatis-Plus 接口与自定义 SQL 方法声明
├── model/             # entity / dto / vo 及内部查询模型
├── converter/         # 模型转换，Entity 不直接作为接口响应
├── security/          # 认证、令牌存储与访问控制
├── scheduler/         # 生命周期推进与快照清理
├── simulation/        # 受配置及权限保护的测试模拟入口
└── common/            # 公共响应、异常等基础设施
```

自定义 SQL 位于 `src/main/resources/mapper/`，已显式配置 XML 扫描。Redis 不作为订单、席位或支付事实的存储来源。

## 本地运行

需要 JDK 21、Maven 和 Docker Compose；仓库不包含 Maven Wrapper。以下命令从项目根目录执行，以 PowerShell 为例。

### 1. 配置环境

仅在 `.env` 不存在时复制配置：

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.example -Destination .env
}
```

编辑 `.env`，分别为 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD` 设置不同的随机密码。该文件由 Compose 和 Spring Boot 读取，已被 Git 忽略；使用不加引号的随机字母数字值，避免两种解析方式的转义差异。不要提交真实凭据。

### 2. 启动依赖

```powershell
# 首次使用且本地缺少镜像时执行
docker compose pull
docker compose up -d --pull never --wait --wait-timeout 180
docker compose ps
```

| 服务 | 默认地址 | 默认配置 |
| --- | --- | --- |
| MySQL | `127.0.0.1:3308` | 数据库及应用账号均为 `encore_ticket` |
| Redis | `127.0.0.1:6380` | DB 0，启用密码及 AOF |
| 后端 | `http://localhost:8080` | 本机访问地址；应用未配置仅回环监听，不应直接暴露公网 |

Compose 中数据库端口仅绑定回环地址，数据保存在命名卷。数据库账号只在空卷首次初始化；之后修改 `.env` 不会自动修改已有账号密码。

### 3. 测试并启动应用

```powershell
mvn -B -ntp test
mvn spring-boot:run
```

应用从根目录读取 `.env`，启动时默认执行 Flyway 迁移。首次应使用专用空数据库，不要直接连接来源不明的已有库。迁移初始化角色，但不会自动提供可公开使用的管理员密码；普通用户可通过 `POST /users/register` 注册，管理员及检票角色须在受控环境另行配置。

启动后可通过公开接口检查响应：

```powershell
Invoke-RestMethod -Uri 'http://localhost:8080/movies' -Method Get
```

新库尚无电影时返回空结果是正常情况。请求、登录方式和错误格式见 [API 文档](docs/api/entrypoints.md)。模拟支付及退款入口默认关闭，仅在允许的非生产 profile、显式开关及 ADMIN 授权下注册使用，具体配置见[支付契约](docs/api/payments.md)和[退款契约](docs/api/refunds.md)。

日常停止与恢复：

```powershell
docker compose stop
docker compose start
```

不要使用 `docker compose down -v` 作为日常停止方式，它会删除数据库和 Redis 数据卷。

## 数据库迁移

- 正式脚本位于 `src/main/resources/db/migration/`，当前最高版本为 V30，版本号不连续。新空库按实际脚本执行累计迁移，不手工导入设计稿替代 Flyway。
- `docs/database/schema.sql` 用于阅读当前结构，已执行的历史迁移不可修改，结构变更通过新增版本交付。
- 默认开启迁移及校验，关闭自动建 schema 和 Flyway clean。`FLYWAY_BASELINE_ON_MIGRATE` 默认关闭，仅用于经核验的已有库首次接入，不是修复迁移失败的通用开关。
- 已有数据库升级前需备份并核对版本及存量约束。MySQL 多条 DDL 不保证整体回滚，失败后先检查实际结构和 history，不盲目 repair、重复执行或用 clean 回滚。
- 测试库累计迁移成功不代表某个本地或生产数据库已经完成升级。

## 测试与验证

验证分层进行，不能用单元测试替代数据库锁竞争，也不能把 HTTP 受理成功当作退款最终完成。

| 层级 | 验证内容 | 证据边界 |
| --- | --- | --- |
| 默认回归 | 规则、Service、Controller、安全链、序列化及部分 H2/XML 行为 | 真实依赖测试默认按开关跳过，跳过不算通过 |
| 真实 MySQL / Redis | 持久化、约束、事务回滚、指定方向的锁竞争、令牌存储和撤销 | 使用专用实例及测试库；不能由 H2 或 mock 推断 |
| 真实 HTTP 闭环 | HTTP → Bearer/Redis → Service → MySQL → 模拟结果事件 | 核对订单、席位、票券及退款终态，而非仅统计 200 |
| 有限性能实验 | 查询、分散购票、热点竞争及退款恢复，结合资源观测与一致性检查 | 本机同机、模拟渠道、有限负载，不代表生产容量 |

常用检查：

```powershell
mvn -B -ntp test
python scripts/check_java_type_imports.py
python -m unittest discover -s scripts -p 'test_*.py' -v
```

Python 为辅助工具需要，普通 Maven 构建不强制依赖 Python。真实测试需按各夹具的开关及环境变量单独配置；部分测试会建库、迁移、删除夹具数据或清理自建 schema，必须先阅读实现和隔离要求，不能直接对业务库开启。

性能资产及使用限制见 [tests/performance/README.md](tests/performance/README.md)。截至 2026-11-07 已完成批准范围内的有限实验和接管报告；负载采用闭环线程及固定请求节奏，且应用、驱动和数据库共享本机资源，不据此声称找到饱和点或达到某个容量 SLA。条件、失败记录和结果见[性能实验与接管记录](outputs/task20-performance-handover-20261107.md)。原始运行产物可能仅保留于本地，文档链接不保证包含全部日志。

## 文档导航

| 文档 | 建议阅读目的 |
| --- | --- |
| [领域模型](CONTEXT.md) | 理解 Order、Payment、Ticket、Refund 及业务边界 |
| [架构决策](docs/adr/) | 了解占座期限、模拟支付边界、时间与认证设计的取舍 |
| [接口目录](docs/api/entrypoints.md) / [通用契约](docs/api/common-contract.md) | 定位入口、权限、请求响应与错误语义；目录中的规划说明不等于已注册接口 |
| [订单](docs/api/orders.md) / [支付](docs/api/payments.md) / [退款](docs/api/refunds.md) | 追踪主交易链、幂等与异常补偿 |
| [核销](docs/api/ticket-verifications.md) / [影厅](docs/api/halls.md) / [场次](docs/api/screenings.md) | 核对入场权益、维护资格和生命周期边界 |
| [数据库设计](docs/database/schema.sql) / [状态流转](docs/domain/state-transitions.md) | 对照持久化模型与业务不变量 |

建议从“创建订单 → 支付通知结算 → 票券生成”阅读代码，再追踪取消/过期后的迟到收款补偿及退款/核销竞争。项目保留设计取舍与失败证据；已实现功能、限定验证结果和生产可用性是三个不同层次。
