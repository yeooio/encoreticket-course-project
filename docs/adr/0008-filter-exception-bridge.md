# 过滤器异常桥接到统一 API 错误处理

状态：accepted

## 背景

`RedisTokenAuthenticationFilter` 在 `DispatcherServlet` 之前执行。Redis 连接失败、命令超时或登录态数据损坏时，异常不会进入 Controller 层的 `@RestControllerAdvice`，直接交给容器处理可能产生不一致的默认错误响应。

项目要求所有错误响应使用统一的 `code`、`message`、`data`、`requestId` 结构，并返回 `X-Request-Id`。同时，Redis 不可用时必须 fail-closed，不能降级为匿名用户。

## 决策

- `ExceptionBridgeFilter` 包裹下游过滤器链中未处理的 `Exception`，明确注入 MVC 的 `handlerExceptionResolver`，复用 `GlobalExceptionHandler`。
- 桥接器只负责转交：返回非 null 视为已处理；返回 null 时原异常继续传播；Resolver 调用抛出的异常直接传播。原故障保存在请求属性中，供最终出口关联诊断。
- 桥接器不再依赖 `ObjectMapper`，不再手写或重试错误响应。响应已提交时跳过 Resolver，仍然传播原异常。
- 已提交响应的故障无法由错误出口形成新结果，桥接器仅在该分支补记安全诊断后重抛；常规异常转交不重复记录应用错误日志。
- 不为 Redis 异常新增通用应用异常类；Redis 访问异常保留原始类型，会话 JSON 解析失败继续使用现有 `IllegalStateException`。
- `GlobalExceptionHandler` 对未预期异常统一返回 `500 / INTERNAL_ERROR`。日志使用 `ExceptionLogSupport` 记录 requestId、方法、原始路径、异常及 cause/suppressed 的类型和栈帧，并补充经过筛选的诊断字段；诊断大小有界，同一请求内按异常身份去重。
- 日志只从明确的 API 读取 `ApplicationException` 的 ErrorCode 枚举 code 及默认说明、符合格式的 SQLState、数据库错误编号、JSON 有效数值行列。普通异常不读取或匹配原始 message，不维护固定文案白名单；每个 cause/suppressed 节点独立筛选，不把原始 Throwable 交给日志框架再次展开。
- 总诊断长度不超过 16,384 个字符。确实省略后续内容时补充 `[diagnostic truncated]`，标记本身计入总长度；完整诊断恰好达到上限时不误标。栈帧数量和异常图数量/深度的独立上限继续保留各自提示。
- 前端错误响应与日志格式独立。参数绑定失败使用字段格式提示；正常字段校验仅保留已确认来自项目 DTO 约束声明的固定提示，未知来源和非字段错误使用通用提示，不回显框架转换原文或被拒绝值。
- 容器 ERROR 分派统一进入 `ApiErrorController` 的 `/error`，复用 `ApiErrorResponse`。保留有效 4xx/5xx 状态；未细分的 4xx 使用 `HTTP_CLIENT_ERROR`，5xx 使用 `INTERNAL_ERROR`；requestId 缺失时补建。
- `/error` 支持原始请求方法，显式返回 JSON；ERROR 分派规则优先于路径授权规则，普通 `/error` 请求仍受认证保护，已认证直接访问返回 404。
- 当前 Servlet 6.1/Tomcat 11 将 ERROR 分派的方法设为 GET，原方法保存在 `RequestDispatcher.ERROR_METHOD`，诊断日志读取该属性。容器清除旧正文和 Content-Length 后，出口显式设置 UTF-8，避免旧 Writer 字符集阻止 JSON 转换。
- 401 继续由 `AuthenticationEntryPoint` 处理，403 继续由 `AccessDeniedHandler` 处理；Advice 将认证/授权异常交回 Security。Redis 故障不转换为 401。
- ERROR 出口自身失败时，Advice 继续传播，不构造第二份响应；不重试写出、不递归调用 Resolver 或 `/error`。
- `RequestIdFilter`、`ExceptionBridgeFilter` 和 `RedisTokenAuthenticationFilter` 只加入 Security Filter Chain，关闭 Servlet 容器的重复自动注册。
- 三个 Filter 默认跳过 ERROR/ASYNC，错误分派不重新生成 requestId 或查询 Redis。
- 故障注入验证了 Spring/Tomcat 默认的三类异常日志会展开原始消息。通过 `FrameworkExceptionLogFilter` 与 `logback-spring.xml` 定向替换已识别的这些日志，保留来源、级别、Marker 及安全堆栈；不关闭整个框架日志分类，也不承诺自动脱敏任意第三方日志。框架升级需复跑相关测试，核对消息前缀。

## 决策修订

2026-09-25：将原决策中的“桥接器手写 JSON 兜底”改为容器 ERROR 出口。原实现可能捕获第一次写出失败并再次写出，既无法保证网络恢复，又模糊了异常桥接的职责。未解析的异常必须继续传播，不能静默正常结束。

同时将仅记录异常类型的日志升级为安全的堆栈诊断。阿里巴巴《Java 开发手册·黄山版》的异常处理、日志规约约束了异常边界、诊断和脱敏；它没有规定必须采用 `/error` 或固定数量的 catch。本决策是结合 Spring MVC/Servlet 生命周期作出的项目选择。

Advice 方法抛出新异常时，Spring 的 `ExceptionHandlerExceptionResolver` 通常捕获该调用故障并返回 null，后续 Resolver 继续处理原异常；不能把这种情况一概解释为异常直接逃出组合解析器。

2026-09-28 首次修订（固定文案匹配部分已由下述同日再次修订取代）：按异常诊断方案，将“省略所有原始异常消息”细化为“允许少量精确匹配的固定原因，并补充明确来源的诊断字段”。统一删除消息会丢失已知故障原因；直接恢复任意 message 又可能把驱动参数或登录态数据带入日志。当时选择有界、可审阅的诊断增量：固定原因必须完整匹配，SQLState 仅接受 5 位大写字母或数字，JSON 位置只取正数行列，不输出 SQL、冲突值、源 JSON 或 location 的字符串表示。未知故障的自由文本仍可能丢失，新增允许内容需同步测试。

应用与已匹配的框架故障日志继续共用同一格式化实现，保留既有 Logback 接入、身份去重和各项上限。文本控制字符清理仅改善日志格式，不是通用敏感信息脱敏。参数绑定响应单独修正，服务端诊断字段不进入 `ApiErrorResponse`。

2026-09-28 再次修订（当前有效）：用户确认采用最小简化方案，取消六条固定异常文案的精确匹配，仅保留明确类型提供的结构化诊断。固定文案匹配使抛出位置的文字与日志工具产生维护耦合；删除匹配可以避免修改异常文字时同步维护白名单。代价是原先六种故障不再单独输出该文字原因，需要结合事件描述、类型、栈帧和异常链排查。保留 `ApplicationException` 的枚举默认说明不意味着允许任意自定义 message，也不将基础设施异常改造成业务异常。

同次修订修复“缓冲区恰好写满、还有内容被省略时缺少截断提示”的边界；这不属于负下标越界。同步请求内按对象身份去重、null 异常的上下文记录、框架匹配范围与日志配置继续沿用，未引入同一请求并发调用保证或跨 JVM 去重。客户端响应分类和文案规则保持不变。实施与验证记录见 `.scratch/exception-diagnostics/spec.md`。

## 影响

- 过滤器层和 Controller 层的错误响应格式一致。
- Redis 故障不会错误地触发前端重新登录流程。
- 未处理错误依赖容器 ERROR 分派和 MVC 序列化；真实 Tomcat 测试覆盖 POST/PATCH、HTML Accept、部分未提交正文、状态和 requestId。
- JSON 契约适用于应用能接管且响应仍可写的请求。响应已提交、客户端断开、进程退出时不能保证替换或交付 JSON；HEAD 没有响应正文。
- 过滤器顺序和 Bean 的自动注册需要通过上下文测试持续验证。
