# V26 正式迁移：场次约束收紧

原候选已经晋级为正式 [V26 迁移](../../../../src/main/resources/db/migration/V26__screening_snapshot_constraints.sql)，由应用的 `classpath:db/migration` 扫描并进入 JAR。本目录仅保留发布说明，不保留第二份 SQL。`docs/database/schema.sql` 已对齐 V1/V2/V3/V20–V26 的累计结构；代码包含 V26 不代表任一目标业务库已经升级。

## 发布前提

1. 确认目标数据库已经完整执行 V25，原有约束启用，Flyway validate 通过。预检 SQL 不负责修复结构漂移。
2. 与已适配的电影/场次写入口一起发布：填写真实片长和快照，并遵守正票价、结束时间与售票截止规则。目标数据库仍须在发布时重新预检；不能用此前的空库报告推定当前数据合规，也不能在升级后回退到不填写片长的旧应用。
3. 在停写窗口，用只读连接执行 [预检 SQL](../../screening-v6-preflight.sql)。保留报告，逐项核实；没有返回行才满足本脚本的数据前提。预检会扫描历史数据，影厅重叠检查可能较慢，应先在目标规模副本评估。
4. 电影片长由真实资料提供；场次原始起止差值只能作为核对线索。快照允许不同于电影当前片长，不自动同步。零价、不合法时间、关联缺失或重叠数据须经业务确认处理；不得自动改价、移动履约时间或删除记录。若历史数据不能按新规则修复，应暂停收紧并设计历史兼容方案。
5. 正式资源目录只有一份 V26；验证空库重放、V25 合法数据升级、异常数据在业务表 DDL 前被拒绝、重启 validate，以及当前电影/场次写入口在 V26 下工作后发布。保留 V23/V24/V25 历史测试的显式版本目标，不把历史空片长或零价 fixture 强行塞入 V26。不要配置第二个包含 V26 的目录。

## 预检输出

统一返回 `issue_code`、`entity_id`、`related_id`；一个记录可返回多个问题，空 related_id 表示无关联记录。只输出标识，不导出票务、用户或支付敏感信息。

| 问题码 | 含义 |
|---|---|
| MOVIE_DURATION | 电影片长为空或非正数 |
| SCREENING_DURATION | 场次片长快照为空或非正数 |
| SCREENING_PRICE | 场次票价非正数 |
| SALE_CUTOFF | 售票截止不是放映前 15 分钟 |
| NON_MINUTE_DURATION | 原始起止差值不是整分钟 |
| END_DURATION | 原始起止差值与片长快照不一致 |
| MISSING_MOVIE / MISSING_HALL | 场次关联的电影/影厅不存在 |
| BEFORE_RELEASE | 未关闭场次早于电影上映日期 |
| SCREENING_STATUS / MOVIE_STATUS / HALL_STATUS | 对应表存在非法状态 |
| HALL_OVERLAP | 同厅两场区间含清场时间相交；related_id 为另一场次 |

影厅区间按左闭右开处理，恰好接壤不冲突。仅 CLOSED 释放区间；OFF_SALE、ENDED 仍计入。此预检覆盖场次排片关联，不是全库订单、支付、票券关系审计，也不取代未来服务的事务并发协议。

## 执行与失败处理

正式脚本先通过临时表 CHECK 拒绝预检识别的异常，再修改业务表；临时检查没有通过时不会先收紧电影表。Flyway 可能留下失败版本记录，应先调查原因和实际结构，不能自动 repair 或反复重试掩盖失败。

停写必须持续到迁移结束；预检与 DDL 之间没有业务锁。磁盘、权限、连接故障仍可能让多条 DDL 部分完成；本方案不保证整体事务回滚。回滚优先前向修复；不得退回无法写入非空片长的旧应用。需要撤销约束时使用经评估的新补偿迁移，不修改已执行版本。

脚本修改 orders.expires_at 的说明，但不改历史订单的值。新订单截止规则属于后续订单服务实现，不能因为列注释更新就视为已完成。

## 隔离验证

设置 `FLYWAY_POC_PASSWORD` 后执行以下命令；URL 必须指向明确可丢弃的 `encore_ticket_flyway_poc_*` 数据库。测试账号需创建/删除测试 schema 的权限。测试自行创建随机 schema，只删除自己创建的 schema。

```powershell
mvn -B -ntp test '-Dtest=ScreeningConstraintsMySqlTests' '-Dflyway.poc.enabled=true' '-Dflyway.poc.username=root' '-Dflyway.poc.url=jdbc:mysql://127.0.0.1:13384/encore_ticket_flyway_poc_screening_checks?connectionTimeZone=UTC'
```

此测试只读取正式 `classpath:db/migration`，通过显式 V25 目标构造升级前数据，再执行正式 V26；不再扫描原候选目录。测试通过不表示目标业务存量已核验，也不会升级业务数据库。
