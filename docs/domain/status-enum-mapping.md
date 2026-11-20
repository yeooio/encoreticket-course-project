# 状态枚举与数据库值映射

数据库表中的状态字段使用 `TINYINT` 保存，Java 代码使用强类型枚举，接口层返回稳定的业务字符串。三层值不直接混用：数据库数字只用于持久化，Java 枚举用于业务判断，接口字符串用于前后端契约。

## 映射规则

- Java 枚举常量使用大写英文和下划线，名称表达业务含义。
- 持久化枚举显式声明数据库值（代码中可命名为 `dbValue` 或 `code`），禁止在业务代码中直接比较魔法数字。纯查询展示枚举不声明数据库值。
- 接口返回 `apiCode`，不直接返回数据库数字。
- 数据库中不存在的状态值视为数据异常，由持久化转换层报告，不静默当作默认状态。
- 终态仍保留在枚举中，Service 层负责阻止非法转换。

## 状态映射

| 表 | Java 枚举 | 数据库值 | 接口状态字符串 | 中文含义 |
|---|---|---:|---|---|
| `sys_user.status` | `NORMAL` | 0 | `NORMAL` | 正常 |
| `sys_user.status` | `DISABLED` | 1 | `DISABLED` | 禁用 |
| `movie.status` | `ONLINE` | 0 | `ONLINE` | 上架 |
| `movie.status` | `OFFLINE` | 1 | `OFFLINE` | 下架 |
| `hall.status` | `ENABLED` | 0 | `ENABLED` | 启用 |
| `hall.status` | `DISABLED` | 1 | `DISABLED` | 停用 |
| `seat.status` | `ENABLED` | 0 | `ENABLED` | 启用 |
| `seat.status` | `DISABLED` | 1 | `DISABLED` | 停用 |
| `screening.status` | `WAIT_SALE` | 0 | `WAIT_SALE` | 待开售 |
| `screening.status` | `ON_SALE` | 1 | `ON_SALE` | 开售 |
| `screening.status` | `OFF_SALE` | 2 | `OFF_SALE` | 停售 |
| `screening.status` | `CLOSED` | 3 | `CLOSED` | 关闭 |
| `screening.status` | `ENDED` | 4 | `ENDED` | 已结束 |
| `orders.status` | `WAIT_PAY` | 0 | `PENDING_PAYMENT` | 待支付 |
| `orders.status` | `PAID` | 1 | `PAID` | 已支付 |
| `orders.status` | `CANCELLED` | 2 | `CANCELLED` | 已取消 |
| `orders.status` | `EXPIRED` | 3 | `EXPIRED` | 已过期 |
| `orders.status` | `REFUNDING` | 4 | `REFUNDING` | 退款中 |
| `orders.status` | `REFUNDED` | 5 | `REFUNDED` | 已退款 |
| `screening_seat.status` | `AVAILABLE` | 0 | `AVAILABLE` | 可售 |
| `screening_seat.status` | `HELD` | 1 | `HELD` | 占用 |
| `screening_seat.status` | `SOLD` | 2 | `SOLD` | 已售 |
| `screening_seat.status` | `DISABLED` | 3 | `DISABLED` | 禁售 |
| `payment.status` | `PENDING` | 0 | `PENDING` | 待支付 |
| `payment.status` | `SUCCESS` | 1 | `SUCCESS` | 支付成功 |
| `payment.status` | `FAILED` | 2 | `FAILED` | 支付失败 |
| `ticket.status` | `VALID` | 0 | `VALID` | 有效 |
| `ticket.status` | `USED` | 1 | `USED` | 已核销 |
| `ticket.status` | `VOID` | 2 | `VOID` | 已作废 |
| `refund.status` | `PENDING` | 0 | `PENDING` | 待处理 |
| `refund.status` | `SUCCESS` | 1 | `SUCCESS` | 退款成功 |
| `refund.status` | `FAILED` | 2 | `FAILED` | 退款失败 |

订单数据库值 `0` 在 Java 中命名为 `WAIT_PAY`，接口统一返回 `PENDING_PAYMENT`，这是为了兼容现有订单状态图和对外契约。其他持久化状态暂时保持 Java 枚举名与接口字符串一致。

表中 `ticket.status` 三行描述 `TicketStatus` 的持久化映射，不表示查询响应直接照抄数据库值。本人票券查询的 `status` 由 `TicketDisplayStatus` 表达，具体派生规则见[状态转换矩阵](state-transitions.md)和[接口文档](../api/tickets.md)：

| 展示枚举 / 接口字符串 | 数据库编码 | 派生条件（必要关联与编码合法后按顺序判断） |
|---|---|---|
| `USED` | 无独立展示编码；来源为票券 1 | 票券 `USED`，优先保留终态 |
| `VOID` | 无独立展示编码；来源为票券 2 | 票券 `VOID`，优先保留终态 |
| `REFUNDING` | 无 | 票券 `VALID(0)` 且订单 `REFUNDING(4)`，优先于过期 |
| `EXPIRED` | 无 | 票券 `VALID(0)` 且订单 `PAID(1)`，场次 `endTime <= now` |
| `VALID` | 无独立展示编码；来源为票券 0 | 票券 `VALID(0)` 且订单 `PAID(1)`，场次 `endTime > now` |

`TicketDisplayStatus` 不使用 `@EnumValue`，其 `EXPIRED/REFUNDING` 不增加 `ticket.status` 编码或数据库 `CHECK` 取值；GET 不写回派生状态。本次查询无需数据库字段、索引或迁移变更，也不新增业务缓存。

票券展示 `EXPIRED` 与订单持久化 `EXPIRED(3)` 是不同概念；票券展示 `REFUNDING` 来源于订单状态，不能将订单的数据库值 4 写入票券状态字段。详情的 `orderStatus` 仍按上表订单映射独立返回。

查询实现由 Mapper 以整数投影读取状态，Service、`TicketStatusResolver` 与 Converter 校验和转换，不直接把投影或数据库编码暴露给 HTTP。有效票关联非 `PAID/REFUNDING` 订单以及未知编码视为内部异常，不能映射成默认 `VALID`。终态优先不豁免必要关联检查；本规则不替代核销或退款写业务校验。

## 代码约束

- Entity 字段可以使用对应枚举，或使用项目统一的枚举转换器；不得让 Service 接收任意整数状态。
- 采用枚举字段时，用 `@EnumValue` 标注数据库值，由 MyBatis-Plus 的 `CompositeEnumTypeHandler` 完成数据库数字与枚举之间的映射；接口层再由 Converter 转换为 `apiCode` 字符串。
- 状态转换集中在领域 Service 或状态转换方法中，Controller 不直接设置状态值。
- 新增持久化状态必须同时更新数据库 `CHECK` 约束、Java 枚举、接口契约和状态转换文档；新增查询派生展示状态只更新展示枚举、派生规则、筛选及接口文档，不因此增加数据库编码或迁移。
- 前端只依赖接口状态字符串，不依赖数据库编码。
