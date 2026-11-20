# Encore Ticket Backend：MySQL 迁移框架调研

> 调研日期：2026-09-11  
> 范围：围绕当前 Java 21 + Spring Boot 4.1.1 + MyBatis-Plus + MySQL 8.4 项目，比较可落地的 schema migration 工具。  
> 资料原则：优先使用 Spring、MySQL、各项目官方文档或官方仓库；“适合度”是结合本仓库现状的工程判断，不是性能基准。

## 结论摘要

当前代码仓库仍是尚未实现业务模块的单模块 Spring Boot 骨架；本地 MySQL `8.4` 实例原先建有 13 张 InnoDB 表且当前无业务数据。现已以 `docs/database/schema.sql` 的 14 张表设计作为唯一 schema 真相源，重建开发库并完成 Flyway baseline。

建议按以下顺序决策：

1. **首选 Flyway，但必须先做 MySQL 8.4 的 PoC。** Spring Boot 对 Flyway 有原生自动配置；Flyway 的版本化 SQL、schema history、checksum、并发锁与 MyBatis-Plus 解耦，最符合当前“小而清晰”的目标。风险是 Flyway 当前官方 MySQL 页面列出的验证版本为 5.7、8.0、9.4，支持矩阵也未列出 8.4；这不是“必然不能用”，而是不能跳过实测和版本钉死。
2. **若要求官方明确验证 MySQL 8.4，或需要更强的变更治理，则选 Liquibase（建议 formatted SQL）。** Liquibase Community 5.0 官方页面明确列出 MySQL Server 8.4，且有 changeset、precondition、context/label、checksum、lock 和 rollback 能力。代价是模型和流程更重，当前 Community 采用 FSL 许可证，需要法务/合规确认。
3. **MyBatis Migrations 只作为“全 Apache、DBA/CLI 主导、愿意自行接入 Spring”的备选。** 它与 MyBatis 生态相邻、SQL-first 且有 up/down，但官方资料的主路径是独立 CLI/repository；接入 Spring Boot 启动顺序、部署锁和运维约束需要项目自行设计。
4. **Atlas 适合未来的 schema-as-code/多服务治理，不适合当前第一步；Dbmate 适合跨语言轻量 CLI，不适合作为本 Java 服务的默认内嵌方案。**

这里的“首选”有一个明确门槛：在仓库实际使用的 `mysql:8.4` 镜像上验证 clean install、重启、并发启动、失败恢复和 checksum 校验；PoC 不通过时直接切换到 Liquibase，而不是在生产环境盲目升级/降级 Flyway。

## 1. 当前项目盘点

| 观察项 | 证据 | 对迁移方案的含义 |
| --- | --- | --- |
| Spring Boot 版本 | [`pom.xml`](../../pom.xml)，parent 为 4.1.1 | 可以直接使用 Boot 官方的 Flyway/Liquibase 自动配置。 |
| Java 版本 | [`pom.xml`](../../pom.xml)，`java.version=21` | 主流候选的 Java 运行要求均可满足；不需要为迁移工具降低 JDK。 |
| ORM/数据访问 | [`pom.xml`](../../pom.xml)，`mybatis-plus-spring-boot4-starter` 3.5.17 | MyBatis-Plus 负责运行时数据访问，不负责生产 schema 版本管理；迁移工具应独立于 Mapper/Entity。 |
| 数据库驱动 | [`pom.xml`](../../pom.xml)，`mysql-connector-j` runtime | JDBC 连接已经存在，迁移工具可复用主 DataSource 或使用专用迁移连接。 |
| 数据库版本 | [`compose.yaml`](../../compose.yaml)，`mysql:8.4` | 这是兼容性决策门槛；本地与 CI 应尽量使用同一大版本。 |
| 连接与字符集 | [`application.yaml`](../../src/main/resources/application.yaml)、[`compose.yaml`](../../compose.yaml) | 当前 URL 使用 MySQL JDBC；设计稿统一使用 `utf8mb4`，迁移脚本与实际服务器默认排序规则需在验证中确认。 |
| 现有 schema 状态 | 本地 `encore_ticket` 已按设计稿重建为 14 张 InnoDB 表，业务表行数估计均为 0，存在成功的 `BASELINE(v1)` | 设计稿已成为唯一 schema 真相源；后续只通过 Flyway 版本脚本变更。 |
| 当前迁移能力 | `pom.xml` 已加入 Spring Boot Flyway starter、`flyway-mysql`，正式 `db/migration/V1__baseline_schema.sql` 已严格对应设计稿 | 已完成真实开发库重置与一次性 baseline；生产发布权限仍需单独落地。 |
| 部署形态线索 | README 目前只描述本地 Compose 和 `mvn spring-boot:run` | 早期可用启动时迁移；上线多副本前应改为单独的 release/CI migration job 或明确锁策略。 |

### 现状风险

- 不能把 `schema.sql`、`data.sql`、MyBatis 初始化脚本与高层迁移工具并行作为生产真相源。Spring Boot 官方建议 Flyway 或 Liquibase 启用后单独负责 schema 初始化，并明确不建议混用，见 [Spring Boot Database Initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)。
- `.env` 含本地凭据，调研没有读取或复制其内容；迁移配置应继续使用环境变量/密钥管理，不把密码写入 changelog、命令日志或文档。
- MySQL 8.4 的 atomic DDL 不是 transactional DDL：DDL 会隐式结束当前事务。迁移工具报告“事务”不能等同于任意多条 DDL 可以整体回滚。

## 2. 评价维度

本次比较使用以下维度，按当前项目的实际风险排序：

1. Spring Boot 4.1.1 的集成和启动顺序是否自然；
2. MySQL 8.4 的官方验证证据和 JDBC 兼容性；
3. SQL-first 程度，以及能否表达 MySQL 特有 DDL/存储对象；
4. 迁移历史、checksum/完整性和 out-of-order 检测；
5. 多实例同时执行时的锁与失败恢复；
6. rollback 的可操作性（同时考虑 MySQL DDL 的隐式提交）；
7. CI/CD、离线脚本、测试环境的可用性；
8. 依赖、运行方式、许可证和长期运维成本。

## 3. 主流候选总览

| 工具 | 典型模型 | Spring Boot 集成 | MySQL 8.4 官方证据 | 历史/完整性 | 并发执行 | 许可证/运维 | 当前项目判断 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Flyway** | 版本化 SQL/Java，另有 repeatable | **原生**：`spring-boot-starter-flyway` + MySQL 模块 | **需验证**：官方 MySQL 页面列 5.7/8.0/9.4，未列 8.4 | `flyway_schema_history`、checksum、validate/repair | 使用数据库锁协调多节点 | Community 核心 Apache-2.0；高级命令分层 | **首选，PoC 通过后采用** |
| **Liquibase** | changeset/changelog；SQL、YAML、XML、JSON | **原生**：`spring-boot-starter-liquibase` | **明确**：Community 5.0 列 MySQL 8.4 | `DATABASECHANGELOG`、checksum、contexts/labels/preconditions | `DATABASECHANGELOGLOCK` | Community 5.0 为 FSL；功能丰富、流程较重 | **8.4/治理优先时首选** |
| **MyBatis Migrations** | 时间戳 SQL，单文件 up + `//@UNDO` down | 无同等级 Boot starter；可 CLI 或 Runtime API 自接 | 面向 JDBC/多数据库，需项目实测 | `CHANGELOG`，版本/状态/审计 | 需自行验证启动锁和部署策略 | Apache-2.0；工具链额外维护 | **特定约束下备选** |
| **Atlas** | versioned 或 declarative schema-as-code | 外部 Go CLI/Docker；无 Boot starter | MySQL workflow 有官方示例，具体镜像版本仍需实测 | `atlas_schema_revisions`、`atlas.sum`；lint 属标准发行版/Pro | MySQL `GET_LOCK` advisory lock | Community Apache 2；标准发行版/Pro 为 MSA/商业能力 | **未来治理/多服务时考虑** |
| **Dbmate** | 时间戳 SQL，`migrate:up/down` | 外部独立二进制；无 Boot starter | 支持 MySQL，版本级验证需自行做 | `schema_migrations` 只记版本；可导出 `schema.sql` | README 未提供 Spring Boot 式启动协调；多副本需单独验证 | MIT；轻量但需引入 Go CLI 运维 | **不建议作为默认方案** |

“强/弱”是工程适配判断，不是工具质量排名。所有候选都不能替代备份、灰度、在线 DDL 评估和回滚预案。

## 4. 逐项调研

### 4.1 Flyway

**官方行为和集成**

- Spring Boot 官方文档把 Flyway 与 Liquibase 列为两种高层迁移工具；Flyway 默认从 `classpath:db/migration` 扫描 `V<VERSION>__<NAME>.sql`，Boot 启动时调用 `Flyway.migrate()`，也支持 `spring.flyway.locations`、Java migration 和独立 `@FlywayDataSource`。见 [Spring Boot Database Initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)。
- MySQL 支持在 Flyway 中是独立模块；官方页面给出的开源 Maven 坐标为 `org.flywaydb:flyway-mysql`。见 [Flyway MySQL driver reference](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)。
- Flyway 在 schema 中建立 `flyway_schema_history`，记录已执行版本、状态和 checksum；`validate` 会比较名称、类型、版本和 checksum，`repair` 用于在人工处理后重新对齐历史。见 [Flyway schema history table](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/flyway-schema-history-table) 和 [validate](https://documentation.red-gate.com/flyway/reference/commands/validate)。
- 官方 FAQ 说明多节点可以并行触发迁移，Flyway 使用数据库锁协调；每个 migration 默认独立事务，但不支持 transactional DDL 的数据库可能无法完整回滚失败前已执行的 DDL。见 [Flyway FAQ](https://documentation.red-gate.com/flyway/reference/usage/frequently-asked-questions)。

**与本项目的优点**

- 只需要在 Maven 中增加 starter 和 MySQL 数据库模块，不需要改 Controller、Service、Mapper 或实体层。
- SQL-first 与 MySQL 项目吻合，`DELIMITER`、mysqldump 产生的 DDL 等场景有官方说明；迁移文件可直接进入 Git review。
- 版本历史和 checksum 适合从空库开始，`V1__baseline.sql` 可以同时成为开发、测试、部署的共同起点。
- Boot 会把数据库初始化 bean 与依赖数据库的 bean 排序，早期单实例启动时实现简单。
- Community 核心仓库采用 Apache-2.0；但当前 Flyway 命令表显示 `migrate/info/validate/repair/baseline` 属于 Community，而 `undo`、dry-run、diff/generate 等能力有 Teams/Enterprise 分层，不能把宣传页面中的全部能力当作免费能力。见 [Flyway commands](https://documentation.red-gate.com/flyway/reference/commands) 和 [官方仓库许可证](https://github.com/flyway/flyway)。

**关键风险**

- [MySQL driver reference](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql) 当前列出的 Verified Versions 为 5.7、8.0、9.4；[支持矩阵](https://documentation.red-gate.com/fd/supported-databases-and-versions-143754067.html) 的 MySQL 行列 9.4、8.3、8.2、8.1、8.0、5.7、5.6，也没有 8.4。两个官方页面的版本列表本身并不完全一致，但共同点是**没有 8.4**。因此本项目不能仅凭“支持 MySQL”得出 8.4 已获官方验证。
- MySQL DDL 隐式提交意味着一个 migration 内多条 DDL 失败后可能留下部分结构；要按小步拆分、优先向前修复，并记录人工恢复步骤，不把 `repair` 当作数据回滚。
- 启动时自动迁移会把数据库变更权限放进应用进程。多副本部署前应优先用一次性的部署任务执行迁移，再以 `spring.flyway.enabled=false` 启动业务实例，或至少验证锁等待/超时和失败告警。

**适合本项目的结论**：高。只要精确的 MySQL 8.4 PoC 通过、版本和模块被钉住，Flyway 是最低复杂度的默认方案。

### 4.2 Liquibase

**官方行为和集成**

- Spring Boot 官方文档说明加入 `spring-boot-starter-liquibase` 后会在应用启动和测试前运行迁移，默认 master changelog 为 `db/changelog/db.changelog-master.yaml`，也支持 SQL、XML、YAML、JSON 和自定义 DataSource。见 [Spring Boot Database Initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)。
- Liquibase Community 5.0 的 MySQL 集成页面明确列出 MySQL Server 8.4、8.0、5.7 为 Verified database versions。见 [Connect Liquibase with MySQL Server](https://docs.liquibase.com/community/integration-guide-5-0/connect-liquibase-with-mysql-server) 和 [supported databases](https://docs.liquibase.com/community/integration-guide-5-0/what-databases-are-supported-by-liquibase)。
- changeset 由 `author:id` 加 changelog 路径唯一标识，可设置 preconditions、contexts、labels、`runAlways`、`runOnChange`；默认一个 changeset 在单事务中执行。见 [What is a Changeset](https://docs.liquibase.com/community/user-guide-5-0/what-is-a-changeset)。
- Liquibase 把 checksum 存在 `DATABASECHANGELOG`；并用 `DATABASECHANGELOGLOCK` 防止多个进程同时更新同一数据库。见 [changeset checksum](https://docs.liquibase.com/community/user-guide-5-0/what-is-a-changeset-checksum) 和 [database changelog lock table](https://docs.liquibase.com/pro/user-guide-4-33/what-is-the-database-changelog-lock-table)。
- rollback 可以按 tag/版本执行；模型化 changeset 对部分操作可生成 automatic rollback，formatted SQL 通常应显式写 `--rollback`。见 [rollback](https://docs.liquibase.com/community/reference-guide-5-0/init-update-and-rollback-commands/rollback) 和 [automatic rollbacks](https://docs.liquibase.com/community/user-guide-5-0/what-automatic-rollbacks-does-liquibase-support)。

**与本项目的优点**

- MySQL 8.4 有明确官方验证证据，绕开了 Flyway 当前版本矩阵的首要不确定性。
- 若未来需要多环境条件、数据前置检查、分批发布、审计或多数据库，changeset 元数据比纯 SQL 文件提供更多治理抓手。
- 可以选择 formatted SQL，保留 MyBatis 团队熟悉的 SQL 读写方式，同时只增加 changeset 注释和 rollback 约定。

**关键风险**

- 配置、changelog 层级和属性比 Flyway 更重；对当前空骨架可能是过度设计。
- Community 5.0 官方仓库声明采用 Functional Source License（FSL），并提供两周年后的 Apache-2.0 future license；这不是当前 Apache-2.0 许可，商业交付前需要法务确认。见 [Liquibase Community repository](https://github.com/liquibase/liquibase) 和 [LICENSE.txt](https://github.com/liquibase/liquibase/blob/main/LICENSE.txt)。
- MySQL 的 implicit commit 仍然存在；Liquibase 的 `runInTransaction=true` 不能让多条 MySQL DDL 获得 PostgreSQL 式原子事务。formatted SQL 的 rollback 也必须评估数据是否可逆。

**适合本项目的结论**：中高。若“官方明确支持 8.4”是硬要求，或项目很快需要治理能力，Liquibase 是比 Flyway 更稳妥的选择；否则它的复杂度和许可证是额外成本。

### 4.3 MyBatis Migrations

**官方行为**

- MyBatis 官方把目标定义为一致、可重复、可逆、可审计、自动化和不可变的 schema 演进；命令包括 `init`、`bootstrap`、`new`、`up`、`down`、`version`、`pending`、`status`、`script`。见 [Introduction / lifecycle](https://mybatis.org/migrations/migrate.html)。
- 迁移是 `scripts` 目录中的时间戳 SQL 文件，`-- //@UNDO` 之后写 down SQL；MySQL 多语句脚本需要把 `send_full_script` 设为 `false`。见 [new](https://mybatis.org/migrations/new.html) 和 [init](https://mybatis.org/migrations/init.html)。
- 对已有数据库可以用一次性 `bootstrap.sql` 建立起点；该文件不算可回滚迁移，也不进入审计日志。见 [bootstrap](https://mybatis.org/migrations/bootstrap.html)。
- 也支持 Runtime Schema Upgrade，但应用需要在启动流程中显式调用 `UpOperation#operate()`，并自行提供连接和 migration loader。见 [runtime migration](https://mybatis.org/migrations/runtime-migration.html)。
- 官方仓库标示 Apache-2.0，工具定位为 CLI database migrations tool。见 [mybatis/migrations](https://github.com/mybatis/migrations)。

**与本项目的优点**

- SQL-first、up/down 对 DBA 友好，Apache-2.0 许可边界简单。
- 可生成 offline script，适合不能让应用账号直接改生产库的组织。
- 与 MyBatis 团队认知相近，但不会把迁移逻辑混入 Mapper。

**关键风险**

- 官方集成路径不是 Spring Boot starter；若放进应用启动，需要自行处理 Boot 数据库初始化顺序、失败时阻止服务接流量、并发实例锁、健康检查和指标。
- `bootstrap.sql` 的“只执行一次且不审计”在本项目新库场景没有必要；如果后续引入已有环境，必须补齐基线和审计策略。
- 官方资料列出 `CHANGELOG`、版本和状态命令，但没有在上述主路径中展示与 Flyway/Liquibase 相同的 applied-file checksum 校验流程；采用前应针对团队的并发分支、失败恢复和 drift 检测做额外验证。

**适合本项目的结论**：中。只有在组织明确要求 Apache-2.0、独立 DBA/CLI 发布或 offline script 时才值得优先考虑。

### 4.4 Atlas

**官方行为**

- Atlas 支持 versioned 和 declarative 两种 schema-as-code 工作流；典型流程是 `migrate diff` 生成、CI 检查、CD 推送、`migrate apply` 部署。见 [Applying Schema Migrations](https://atlasgo.io/versioned/apply)。
- applied revisions 写入 `atlas_schema_revisions`；应用时用目标数据库的 advisory lock，MySQL/MariaDB 使用 `GET_LOCK`，并可配置 lock timeout。Atlas 默认按 migration file 包事务，但官方特别警告 MySQL 的 implicit commit 可能导致 schema 与 revision table 不一致。见同一 [apply 文档](https://atlasgo.io/versioned/apply)。
- `atlas.sum` 为迁移目录中的文件和整体树计算 checksum，可把并行分支冲突前移到 Git 合并阶段。见 [Migration Directory Integrity](https://atlasgo.io/concepts/migration-directory-integrity)。
- Community Edition 是 Apache-2.0，包含 MySQL 核心和基础 versioned migrations；lint、down、declarative、drift、CI 等多项能力在标准发行版/Pro 中，使用标准发行版还涉及 Atlas MSA。见 [Community Edition](https://atlasgo.io/community-edition)。

**与本项目的优点**

- 如果将来演进为多个服务、多租户或需要 schema diff/lint/gatekeeper，Atlas 的目录完整性和声明式工作流很有吸引力。
- 可在 CI 中生成和审查 SQL，再由独立 job 部署，不必把迁移权限放入应用。

**关键风险**

- 主要运行形态是外部 Go CLI/Docker；当前项目是单模块 Java 服务，没有现成的工具链或 CI，接入成本明显高于 Boot starter。
- 需要明确使用 Community 还是标准/Pro；如果选 Community，不能默认拥有 lint、down、drift 等治理能力。
- MySQL 8.4 的具体兼容性、生成 SQL 与团队实际 DDL 规范仍须在同版本实例上验证。

**适合本项目的结论**：中低（现在）；高（未来需要 schema-as-code 和跨服务治理时）。

### 4.5 Dbmate

**官方行为**

- Dbmate 是 framework-agnostic 的独立命令行工具，支持 MySQL、MariaDB、PostgreSQL、SQLite、ClickHouse；迁移用纯 SQL、时间戳文件和 `migrate:up/down` 标记。见 [官方仓库 README](https://github.com/amacneil/dbmate)。
- 默认记录表为 `schema_migrations(version)`，应用时只记录版本号而不保存文件内容；默认按数字顺序执行，但独立分支造成的 out-of-order 不会自动阻止，除非启用 strict。见 README 的 [migration files / table / running migrations](https://github.com/amacneil/dbmate)。
- README 宣称迁移在事务中原子执行，并提供单文件 rollback；但这不能消除 MySQL DDL 的隐式提交限制。
- 官方仓库以 MIT license 发布，并强调单一自包含二进制。见 [Dbmate repository](https://github.com/amacneil/dbmate)。

**与本项目的优点**

- 学习和配置成本低，纯 SQL，适合多语言团队共享一套 CLI。
- 可以在部署 job 里运行，避免应用进程持有迁移权限。

**关键风险**

- 没有 Spring Boot 自动配置；需要维护 Go 二进制、命令参数、环境变量和 CI/CD 约定。
- 只记录版本、不记录内容的模型对“应用后误改迁移文件”的发现能力弱于 checksum 型工具；README 也要求修改内容前先 rollback。
- 当前项目没有多语言服务或既有 dbmate 资产，引入外部 CLI 的收益不足以抵消运行和锁策略的额外责任。

**适合本项目的结论**：低。除非组织已经标准化 dbmate，否则不建议选它作为 Java 服务的第一迁移框架。

## 5. MySQL 8.4 对迁移设计的硬约束

MySQL 官方文档指出：8.4 支持 atomic DDL，但 atomic DDL **不是** transactional DDL；DDL 会隐式结束当前事务，不能与其他语句组成一个可由 `START TRANSACTION ... COMMIT` 控制的整体。当前只有 InnoDB 对表相关 atomic DDL 提供支持。见 [MySQL 8.4 Atomic DDL](https://dev.mysql.com/doc/refman/8.4/en/atomic-ddl.html)。

因此无论最终选择哪个工具，都应遵守：

- 一个迁移尽量只做一个可验证的结构意图；大型表的 `ALTER TABLE` 先做锁时间、空间和执行计划评估。
- 不把“工具记录成功”当作业务数据迁移成功；DDL、回填、索引创建和应用发布最好拆为可观察步骤。
- 对不可逆 DDL（删除列/表、截断、改变数据类型）先备份并准备 forward-fix；生产不把工具的 `clean`/`drop` 命令当作回滚手段。
- 应用账号与迁移账号分离：运行时账号只拥有业务所需 DML/受限 DDL，部署 job 才拥有迁移所需权限。
- CI 使用同一 MySQL 大版本和字符集/排序规则，避免在 H2 等替代数据库上“通过”后在 MySQL 失败。
- 多实例部署只允许一个明确的迁移执行者；即使工具有数据库锁，也要设置超时、告警和失败后的人工处置文档。

MySQL 还提供 `GET_LOCK()` 这类会话级命名锁，可用于自定义协调，但它不是事务锁、连接结束会释放，不能替代迁移工具的历史表和失败恢复。见 [MySQL locking functions](https://dev.mysql.com/doc/refman/8.4/en/locking-functions.html)。

## 6. 针对本项目的推荐方案

### 6.1 推荐排序

| 排名 | 方案 | 采用条件 | 不采用/切换条件 |
| --- | --- | --- | --- |
| 1 | **Flyway Community + `flyway-mysql`** | 精确 `mysql:8.4` PoC 通过；依赖版本可锁定；团队接受 Community 能力边界 | PoC 失败、出现未解决的 8.4 方言问题，或必须依赖官方 8.4 验证/更强治理 |
| 2 | **Liquibase Community + formatted SQL** | 需要明确 8.4 验证、preconditions/contexts/rollback；法务接受 FSL | 团队要求纯 Apache-2.0，或当前规模不值得维护 changelog 复杂度 |
| 3 | **MyBatis Migrations** | 组织要求 Apache-2.0、离线脚本/DBA CLI，且愿意自行建设启动与锁策略 | 需要开箱即用的 Boot 集成、checksum 和多实例运维保障 |
| 4 | **Atlas** | 未来明确采用 schema-as-code、跨服务 CI lint/drift | 当前单服务、无 Go/CI 基础设施，或只使用 Community 导致缺少所需治理能力 |
| 5 | **Dbmate** | 已有组织级 dbmate 标准或多语言服务共享工具 | 仅为一个 Spring Boot 服务引入外部 Go CLI |

### 6.2 默认落地形态（不改变现有业务代码）

若 Flyway PoC 通过，建议的目标约定如下（这是后续实现草案，不是本次代码变更）：

```text
src/main/resources/
└── db/migration/
    ├── V1__baseline.sql
    ├── V2__next_schema_change.sql
    └── R__reference_data.sql       # 仅在确有 repeatable 需求时使用
```

- 迁移文件一旦在共享环境执行即视为不可变；修正通过新增版本完成，不编辑旧文件。
- 首个 `V1` 只建立业务 schema、审计字段、字符集、必要索引和约束；不要把本地账号/密码写入 SQL。
- 应用启动时迁移仅适合早期单实例开发/部署；进入多副本后，优先在发布流水线执行一次 `migrate`，业务实例只读已完成 schema。
- CI 至少执行 `validate`、从空库重放全部 migration、从上一版本升级到当前版本，并验证 MyBatis-Plus Mapper 能正常启动。
- 若选 Liquibase，目录可改为 `db/changelog/db.changelog-master.yaml` + `db/changelog/changes/*.sql`；每个 formatted SQL changeset 明确 `author:id` 和 rollback，避免无理由混用 XML/YAML DSL。

### 6.3 版本选择原则

- 不在调研阶段硬编码 Flyway/Liquibase 版本号；先让 Spring Boot 4.1.1 BOM/官方 starter 解析，再用 `mvn dependency:tree` 记录实际版本。
- 同时锁定数据库模块（例如 Flyway 的 `flyway-mysql`），不要只加入核心包；Flyway 官方已将数据库支持拆为独立依赖。
- 记录 JDK、MySQL 镜像 digest/小版本、Connector/J、迁移工具和测试容器版本，避免“本地 8.4 能跑、CI 另一镜像不能跑”。

## 7. 必做 PoC 与验收标准

PoC 不需要实现业务接口，只需在一个临时数据库上验证候选工具。建议顺序：

| 场景 | 验证内容 | 通过标准 |
| --- | --- | --- |
| Clean install | 空 `encore_ticket` 执行 V1，检查 14 张表、索引、检查约束、字符集及“无数据库外键” | 一次成功，history/changelog 表状态正确 |
| Idempotent restart | 相同应用/CLI 重跑 | 无重复 DDL、无 checksum 误报、业务应用可启动 |
| Concurrent start | 两个进程同时迁移 | 只有一个执行者；另一个等待或安全退出；无半成品 history |
| Failed migration | 人为制造 SQL 错误/权限错误 | 错误可观测；能按官方流程 repair/forward-fix；不误报成功 |
| Applied-file mutation | 修改已执行文件后执行 validate/update | 能阻止或明确报告 checksum/内容不一致 |
| MySQL-specific DDL | utf8mb4、索引、`ALTER TABLE`、必要时 procedure/trigger | SQL 与 `mysql:8.4` 实例一致；不会依赖 H2 方言 |
| Data backfill | 小批量 DML 与 DDL 分开 | 明确事务边界、重试和断点；不把不可逆数据变更伪装成 rollback |
| Upgrade path | V1 数据升级到最新版本，再从空库重放 | 两条路径结果一致，应用 Mapper/启动检查通过 |
| Least privilege | 运行账号和迁移账号分开 | 运行账号无法执行危险 schema 操作；发布 job 有最小必要权限 |
| CI reproducibility | 用 Compose/Testcontainers 的 MySQL 8.4 跑完整 suite | 本地、CI 使用同一大版本/排序规则，失败能阻断合并 |
| License review | 核对依赖树和发行版 | Flyway Apache 或 Liquibase FSL/Atlas MSA 的使用边界有书面结论 |

**决策规则**：Flyway 只要在“Clean + Concurrent + Failed + Mutation + Upgrade”任一核心场景失败，就不进入生产；若失败原因与 8.4 版本支持有关，直接执行 Liquibase PoC，不通过手工绕过校验解决。

## 8. POC 后的正式落地清单

| 文件/目录 | 预期动作 | 目的 | 风险 |
| --- | --- | --- | --- |
| `pom.xml` | **已完成**：增加 Flyway starter + `flyway-mysql` | 引入迁移运行时 | 依赖版本、启动失败会影响应用可用性 |
| `src/main/resources/db/migration/` 或 `db/changelog/` | `V1__baseline_schema.sql` 保留历史基线；按顺序应用全部版本迁移后的结构应对应当前 `docs/database/schema.sql`；后续迁移继续追加且不可变 | 建立 schema 真相源 | SQL 错误、锁时间、不可逆 DDL |
| `src/main/resources/application-*.yaml` | **已完成安全开关**：`FLYWAY_ENABLED` 默认开启，显式禁止 clean/自动建 schema；baseline 默认关闭 | 区分正常迁移与一次性接管 | 误把生产迁移开关打开/关闭 |
| CI 配置 | 启动 MySQL 8.4，执行 validate、重放、升级测试 | 防止版本漂移和破坏性变更 | CI 时间、凭据和容器资源 |
| `README.md`/运维文档 | 记录迁移命令、权限、失败恢复、禁止操作 | 让开发/发布流程可重复 | 文档落后于工具配置 |
| 测试目录 | **已完成 POC**：增加隔离 smoke/integration test；仍需补并发/失败恢复 probe | 验证真实 MySQL 方言与运维边界 | 需要可复现数据库环境 |

本次实现修改了 `pom.xml`、生产 Flyway 配置、生产 V1 基线脚本、默认测试开关和测试专用 POC 资源；没有修改业务 Java，也没有对 `encore_ticket` 执行 DDL。

### 验证范围与限制

- 已在本地 `mysql:8.4`（服务器版本 `8.4.10`）上解析并运行 Spring Boot 4.1.1 + Flyway 12.4.0；依赖版本由 Spring Boot BOM 管理。
- POC 使用两个明确命名的临时 schema：空 schema 从正式 V1 重放；复制现有结构的 schema 开启一次性 `baseline-on-migrate` 后验证 V1 不重放。两条路径均通过 `validate()` 与第二次 `migrate()` 幂等检查。
- 初次落地时，正式 `V1__baseline_schema.sql` 与当时的 `docs/database/schema.sql` 建表主体一致，包含 14 张表且不定义数据库外键。后续设计变更通过新增迁移实现；当前设计稿应与累计迁移结果比较，不再要求历史 V1 与最新设计稿相同。
- `docs/database/schema.sql` 已被确定为唯一设计来源，不再与 live 13 表快照并行维护。
- 默认 `mvn clean test` 通过，且 Flyway POC 测试默认跳过；因此普通测试不会在开发者的 `encore_ticket` 中创建历史表。
- 尚未完成并发启动、故障恢复和已执行文件篡改后的 validate probe；这些仍是生产发布前的门槛。Flyway 官方 MySQL 页面/支持矩阵也没有把 MySQL 8.4 列为 Verified，因此结论是“当前镜像实测可用”，不是官方版本认证。

### POC 实测记录（2026-09-12）

| 场景 | 实际结果 | 证据 |
| --- | --- | --- |
| 空 schema 首次迁移 | 创建 `flyway_schema_history`，正式 V1 建立设计稿中的 14 张表 | [`FlywayMySqlPocTests.java`](../../src/test/java/io/github/chenforegone/encoreticket/migration/FlywayMySqlPocTests.java)、[`V1__baseline_schema.sql`](../../src/main/resources/db/migration/V1__baseline_schema.sql) |
| 已有 schema 接入 | 设计稿对应的表结构保留；生成 BASELINE v1，V1 不重放 | 同上；临时库 history 行为为 `BASELINE(v1)` |
| 真实开发库重置 | 原有表无业务数据；按设计稿重建 14 张表，确认 0 个外键后登记 `BASELINE(v1)` | MySQL `encore_ticket` 元数据复核与应用启动日志 |
| 重启/重复执行 | `validate()` 成功，第二次 `migrate()` 报 schema up to date，无新增 history 行 | 同上 |
| 业务库保护 | `safePocUrl` 只允许 `encore_ticket_flyway_poc_*`；`encore_ticket` 未出现 `flyway_schema_history` | POC URL 校验与数据库元数据复核 |

### 电影版本约束补齐（V4）

`V4__movie_version_nonnegative.sql` 为 `movie` 增加 `ck_movie_version CHECK (version >= 0)`，
补齐设计稿已有但 V1–V3 未创建的约束。`version` 仍为 `INT NOT NULL DEFAULT 0`；
历史 V1–V3 不修改，新库按顺序重放到 V4，已有 V3 库向前升级。

升级前检查 `SELECT id, version FROM movie WHERE version < 0` 的结果。若存在负版本号，先定位异常写入和
受影响记录，制定数据修复方案；迁移不自动清零或改写版本号，以免使旧客户端版本重新有效。
MySQL 添加启用的 CHECK 时会验证存量数据，违规数据会使迁移失败；应核查失败原因和 Flyway history，
处理后再按 Flyway 恢复流程执行，不能跳过失败迁移。大表需在发布前评估验证数据和 DDL 锁的耗时。

仅回退应用版本时可保留该约束；如果确需撤销约束，通过后续版本迁移显式移除，不删除已执行 V4 或修改其校验和。
最新 `docs/database/schema.sql` 保留同名同条件约束，作为累计迁移结果的对照。

新增的 [`MoviePersistenceMySqlTests`](../../src/test/java/io/github/chenforegone/encoreticket/migration/MoviePersistenceMySqlTests.java)
通过真实 MySQL 和正式迁移验证版本约束与电影 Mapper 的字面前缀查询。它复用现有 POC 的显式启用方式，
仅接受 `encore_ticket_flyway_poc_*` 基础库 URL，每个用例另建随机命名的专用 schema，结束时只清理该用例创建的库。
测试账号需要创建和删除测试库的权限；密码从 `FLYWAY_POC_PASSWORD`（或 `MYSQL_PASSWORD`）读取。
测试不启动 Spring 上下文或电影发行调度器，也不访问业务库。

在准备好独立 MySQL 8.4 实例和空的 `encore_ticket_flyway_poc_movie_checks` 基础库后，使用以下命令运行
（端口和用户名按隔离测试环境调整，密码通过环境变量提供）：

```powershell
mvn -B -ntp test '-Dtest=MoviePersistenceMySqlTests' '-Dflyway.poc.enabled=true' '-Dflyway.poc.username=root' '-Dflyway.poc.url=jdbc:mysql://127.0.0.1:13384/encore_ticket_flyway_poc_movie_checks?connectionTimeZone=UTC'
```

验收覆盖空库重放、V3 正常数据升级、负数 INSERT/UPDATE 拒绝、存量负数导致升级失败且原值保留、重复迁移，
以及中文、百分号、下划线、转义字符、反斜杠和组合标题的字面前缀查询。状态、日期、稳定排序和分页也通过实际 Mapper 验证。
普通 `mvn test` 默认跳过这些真实数据库用例；只有显式启用且实际执行成功，才能认定数据库验收通过。

2026-09-26 在独立的 Windows MySQL 8.4.10 临时实例上完成实测：标题百分号案例在修复前错误返回相似标题，
负版本 INSERT 案例在 V4 添加前未被拒绝；两项修复后对应测试均通过。显式启用 POC、选择正式迁移目录后，
完整测试集共 196 项通过、0 失败、0 跳过，其中包含 15 项 `MoviePersistenceMySqlTests` 和 1 项既有 Flyway POC。
测试执行于专用临时库，未对业务库运行 V4。全量验证命令如下：

```powershell
mvn -B -ntp test '-Dflyway.poc.enabled=true' '-Dflyway.poc.username=root' '-Dflyway.poc.locations=classpath:db/migration' '-Dflyway.poc.url=jdbc:mysql://127.0.0.1:13384/encore_ticket_flyway_poc_movie_checks?connectionTimeZone=UTC'
```

## 9. 官方资料索引

### Spring Boot

- [Database Initialization（Spring Boot 4.1.1）](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Spring Boot 4.1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)

### Flyway

- [MySQL driver reference](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)
- [Supported databases and versions](https://documentation.red-gate.com/fd/supported-databases-and-versions-143754067.html)
- [Schema history table](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/flyway-schema-history-table)
- [Validate](https://documentation.red-gate.com/flyway/reference/commands/validate)
- [Commands and edition boundaries](https://documentation.red-gate.com/flyway/reference/commands)
- [FAQ: parallel nodes and rollback](https://documentation.red-gate.com/flyway/reference/usage/frequently-asked-questions)
- [Official repository and Apache-2.0 license](https://github.com/flyway/flyway)

### Liquibase

- [Connect Liquibase Community 5.0 with MySQL](https://docs.liquibase.com/community/integration-guide-5-0/connect-liquibase-with-mysql-server)
- [Supported databases](https://docs.liquibase.com/community/integration-guide-5-0/what-databases-are-supported-by-liquibase)
- [Changesets](https://docs.liquibase.com/community/user-guide-5-0/what-is-a-changeset)
- [Changeset checksums](https://docs.liquibase.com/community/user-guide-5-0/what-is-a-changeset-checksum)
- [Rollback](https://docs.liquibase.com/community/reference-guide-5-0/init-update-and-rollback-commands/rollback)
- [Automatic rollback support](https://docs.liquibase.com/community/user-guide-5-0/what-automatic-rollbacks-does-liquibase-support)
- [Community source and license](https://github.com/liquibase/liquibase)
- [FSL-1.1-ALv2 license text](https://github.com/liquibase/liquibase/blob/main/LICENSE.txt)

### MyBatis Migrations

- [Introduction and lifecycle](https://mybatis.org/migrations/migrate.html)
- [Init and MySQL script settings](https://mybatis.org/migrations/init.html)
- [Migration file convention](https://mybatis.org/migrations/new.html)
- [Bootstrap existing database](https://mybatis.org/migrations/bootstrap.html)
- [Runtime schema upgrade](https://mybatis.org/migrations/runtime-migration.html)
- [Official repository](https://github.com/mybatis/migrations)

### Atlas

- [Applying versioned migrations](https://atlasgo.io/versioned/apply)
- [Migration directory integrity](https://atlasgo.io/concepts/migration-directory-integrity)
- [Community Edition and feature/license boundary](https://atlasgo.io/community-edition)
- [Automatic MySQL migrations](https://atlasgo.io/guides/mysql/mysql-automatic-migrations)
- [Official repository](https://github.com/ariga/atlas)

### Dbmate

- [Official repository, supported databases, file model and commands](https://github.com/amacneil/dbmate)

### MySQL

- [MySQL 8.4 Atomic DDL](https://dev.mysql.com/doc/refman/8.4/en/atomic-ddl.html)
- [MySQL 8.4 locking functions](https://dev.mysql.com/doc/refman/8.4/en/locking-functions.html)

## 最终建议（一句话）

**当前已按 `docs/database/schema.sql` 完成 clean schema 固化、真实开发库重置及 `BASELINE(v1)` 接入；建议采用 Flyway Community + `flyway-mysql`，并在生产发布前补充并发/失败恢复 probe，保留 MySQL 8.4 未列入官方 Verified 的限定。**

## 合并恢复后的版本对照（2026-10-19）

上文电影 V3/V4、场次 V5/V6 是恢复分支的历史记录，不再是当前正式资源编号。当前保留 master 已应用的 V3 支付及 V20/V21/V22；电影使用 V23/V24，场次快照扩展与约束收紧使用 V25/V26。V25 保留现有 version、admission_open_time 和 V21 幂等表，缺失片长须核实后补录；V26 先预检再收紧，不自动更改业务数据。部署及测试以这些新编号为准；旧分支已应用的同号不同义历史不能通过改文件或自动 repair 合并。

本次环境 Docker daemon 不可用，默认构建的 MySQL POC 为显式开关跳过；不能把普通 clean verify 成功解释为此次 V23–V26 已在真实 MySQL 8.4 验收。设计稿已同步累计字段、索引和约束，真实库元数据一致性仍由 ScreeningExpansionMySqlTests 显式验收。
