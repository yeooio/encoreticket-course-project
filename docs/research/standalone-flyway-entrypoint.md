# 独立 Java Flyway 入口与 V20 前置回调

## 能力与边界

`io.github.chenforegone.encoreticket.config.StandaloneFlywaySupport` 是项目自己的 Java 主类，不是 Flyway 官方 CLI 扩展。只接受一个命令：`validate` 或 `migrate`，不提供 repair、clean、baseline、info 或任意 Flyway 参数透传。不新增依赖，复用 pom 中的 Flyway core、flyway-mysql 和 Connector/J。

- `main` 不启动 Spring，不加载 application.yaml、`.env` 或 Flyway 配置文件，只读取下表环境变量并显式注册 `ScreeningHistoricalPrecheckCallback`。
- `validate` 只做 Flyway 迁移完整性校验，不执行迁移，也不会触发 `BEFORE_EACH_MIGRATE` 的 V20 历史数据预检。因此 validate 成功不代表历史排片正常。
- `migrate` 启用 validate-on-migrate；真正执行 `V20__screening_admission_support.sql` 前，回调拒绝异常历史数据或查询失败。V20 已应用时不会补跑该预检，也不是通用的全库排片审计工具。
- 不更改 V20 原文或已应用 checksum，不执行 repair，不自动修复数据、接管已有非空 schema 或创建 schema。MySQL DDL 仍可能隐式提交，失败不意味着全部回滚。
- 原 callback 的 `@Component` 保持不变，原 Spring Boot 启动部署仍由 Boot 收集 Bean。**裸 Flyway CLI 不会因为 classpath 中存在普通 Spring Bean 就自动注册它。** 本次没有实现或验证官方 CLI 的 `-callbacks=...` 路径，也不要求安装 CLI。

## 本地隔离库约束

必须事先人工准备独立 MySQL 测试实例、专用账号及已存在的测试 schema。入口不负责创建或清理数据库。

| 环境变量 | 要求 |
| --- | --- |
| `ENCORE_FLYWAY_LOCAL_ENABLED` | 必须精确为 `true` |
| `ENCORE_FLYWAY_LOCAL_URL` | 必须符合下述固定 URL 形式 |
| `ENCORE_FLYWAY_LOCAL_USER` | 非空测试账号，不回退至 MYSQL_USER |
| `ENCORE_FLYWAY_LOCAL_PASSWORD` | 非空密码，由外部安全注入，不回退至 MYSQL_PASSWORD |

URL 示例（非业务库）：

```text
jdbc:mysql://127.0.0.1:13384/encore_ticket_flyway_poc_cli?connectionTimeZone=UTC&characterEncoding=UTF-8
```

只接受字面量 `127.0.0.1`、显式端口 1–65535、`encore_ticket_flyway_poc_` 后至少一个小写字母/数字/下划线，查询参数必须与示例完全一致。拒绝业务库名、域名、多主机、编码路径、额外连接参数等。不支持 localhost/IPv6 别名或参数重排，这是有意的收口。UTC 保持项目既有时间语义。

URL 白名单不能证明本机端口没有转发至业务库；操作者仍须确认实际目标为隔离测试实例，并用数据库账号权限限制其只能访问测试 schema。不要使用业务凭据或依靠改名来绕过保护。本入口不是生产发布工具；若以后需要生产独立 job，应另行评审凭据、目标限制和发布流程，不放宽此入口的本地测试约束。

## 运行命令（准备好构建产物后）

本次未运行 Maven、Java 迁移命令或数据库测试。编译、测试及 runtime classpath 由外部运行器准备。需要 JDK 21，主类及正式迁移资源应在 `target/classes`，项目**完整 runtime 依赖**应展开在 `target/dependency`（包括 Flyway core、flyway-mysql、MySQL JDBC 及其传递依赖）。该目录不是本次自动生成的；缺少依赖时命令不可直接运行。不要拼接一个未经核对版本的独立 CLI 依赖集合。

Windows PowerShell（仓库根目录）：

```powershell
$env:ENCORE_FLYWAY_LOCAL_ENABLED = 'true'
$env:ENCORE_FLYWAY_LOCAL_URL = 'jdbc:mysql://127.0.0.1:13384/encore_ticket_flyway_poc_cli?connectionTimeZone=UTC&characterEncoding=UTF-8'
$env:ENCORE_FLYWAY_LOCAL_USER = 'isolated_test_user'
# ENCORE_FLYWAY_LOCAL_PASSWORD 由外部安全注入，不把密码写进命令历史。
java -cp 'target/classes;target/dependency/*' io.github.chenforegone.encoreticket.config.StandaloneFlywaySupport validate
# 先确认上一条命令成功、测试库可写及备份/恢复方案，再单独执行：
java -cp 'target/classes;target/dependency/*' io.github.chenforegone.encoreticket.config.StandaloneFlywaySupport migrate
```

Windows Git Bash 运行 Java 时同样使用分号并引用 classpath；Linux/macOS 使用冒号 `target/classes:target/dependency/*`。也可由运行器传入自身生成的完整 classpath。不提供 `java -jar` 独立入口：现有 Boot 可执行 jar 仍按原应用入口启动，不能直接把嵌套的 BOOT-INF/lib 当普通 `-cp` 使用。

命令或环境不合要求时在加载 Flyway 前拒绝。Flyway、SQL 或历史预检异常不被吞掉，Java 进程非零退出；不要接着盲目重试 migrate，不要修改历史 SQL、checksum 或执行 repair 来掩盖失败。日志可能来自 JDBC/Flyway，分享日志前应检查并脱敏连接/凭据信息。

## 手工 Java 配置复用

非 Spring 调用方在 `.load()` 前显式调用：

```java
var configuration = Flyway.configure()
        .dataSource(testDataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .createSchemas(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .failOnMissingLocations(true);
StandaloneFlywaySupport.registerCallbacks(configuration);
var flyway = configuration.load();
flyway.migrate();
```

`registerCallbacks` 只负责追加此回调，保留其他回调；对已经存在该类实例的配置不重复追加。它**不提供** main 的目标库/环境保护，手工调用方必须自行实施等价的测试库限制。不要在 Boot 自动收集该 Bean 的路径重复手工注册。可直接复用 `localTestConfiguration(environment)` 获取与 main 一致的受限配置；该方法仅构建配置，不加载或连接 Flyway。

## 离线测试与验证状态

外部运行器应选择 `StandaloneFlywaySupportTest` 及既有 `ScreeningHistoricalPrecheckCallbackTest`。新增测试不连接数据库，覆盖手工注册、保留其他回调、重复注册、本地配置安全开关、缺失环境变量、危险 URL 拒绝、命令白名单、异常传播及手工注册后的历史异常拒绝。

本次只落盘并静态回读，未运行 Maven 或测试；不宣称测试已通过或 MySQL 迁移已验收。真实 MySQL 8.4 的空库重放、V20 前升级和失败恢复应由独立运行器在隔离库另行验证；未修改 `RealHttpTicketingE2ETest`。
