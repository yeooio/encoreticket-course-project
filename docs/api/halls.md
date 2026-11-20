# 管理员影厅与物理座位

所有入口由 `/admin/**` 的 `ROLE_ADMIN` 规则保护；成功使用 `ApiResponse`，错误复用通用体系。不存在/跨厅座位为 404，非法参数为 400，旧版本/编号或坐标重复/占售或场次引用冲突为 `409 RESOURCE_CONFLICT`，停用影厅的布局及座位维护为 `422 BUSINESS_RULE_VIOLATION`。不要求 Idempotency-Key，不承诺重放创建结果。

## 入口

| 方法 | 路径 | 请求及响应 data |
|---|---|---|
| GET | `/admin/halls` | hallNo 精确、hallName 字面包含、status 精确（ENABLED/DISABLED），AND 组合；返回分页 HallDetail |
| POST | `/admin/halls` | `{hallNo,hallName,seats:[{rowNumber,seatNumber}]}`；201 HallDetail + Location |
| GET | `/admin/halls/{hallId}` | HallDetail，停用厅也可查询，不嵌入布局 |
| PUT | `/admin/halls/{hallId}` | `{hallName,version}`；HallDetail |
| POST | `/admin/halls/{hallId}/enable` | `{version}`；HallDetail |
| POST | `/admin/halls/{hallId}/disable` | `{version}`；HallDetail |
| GET | `/admin/halls/{hallId}/seats` | `{hallId,hallVersion,serverTime,seats:[SeatView]}` |
| POST | `/admin/halls/{hallId}/seats` | `{seats:[{rowNumber,seatNumber}]}`；201 `{hallId,seats:[SeatView]}`，只包含本批 |
| POST | `/admin/halls/{hallId}/seats/{seatId}/enable` | `{version}`；SeatView |
| POST | `/admin/halls/{hallId}/seats/{seatId}/disable` | `{version}`；SeatView |

不提供删除、改编号、座位换厅/改位置、任意 status 编辑或旧 PUT 座位入口。

分页默认 page=1、pageSize=20，上限100。sortBy 白名单 createTime（默认）、hallNo、hallName；sortOrder 默认 desc，支持 asc/desc，附加同方向主键排序。total/items 不保证跨语句一致快照。名称的 `%`、`_`、`!` 按字面匹配，编号等值遵循数据库实际 collation，不承诺大小写敏感。

ID 输出字符串；输入路径为正十进制 BIGINT。编号/名称去首尾空白后分别最多32/64个 Unicode 码点，禁止控制字符；名称允许重复。每次创建/追加1～500个非空座位，坐标为严格 JSON 正整数，允许不连续；内部重复400、已有坐标重复409，整批回滚。创建不接受 status/version/id；命令局部拒绝未知字段，版本必须为严格 JSON 非负整数。

## 返回与维护规则

HallDetail：hallId、hallNo、hallName、status、version、seatCount、enabledSeatCount、createdAt、updatedAt、actions。列表复用同一精简条目，不返回实体或物理布局。

Hall actions：canRename、canEnable、canDisable、canViewSeats、canAddSeats、addSeatBlockReasons；原因包括 HALL_DISABLED、HALL_HAS_SCREENINGS。计数和引用资格按本页 Hall 批量查询。

SeatView：seatId、hallId、rowNumber、seatNumber、status、version、canEnable、canDisable、disableBlockReasons、createdAt、updatedAt。后台扁平布局包含停用座位，按排/座/ID升序；原因包括 ALREADY_DISABLED、SEAT_HAS_ACTIVE_HELD、SEAT_HAS_ACTIVE_SOLD。资格只是瞬时提示，写入仍锁后复核；不暴露订单标识或观众身份。技术时间为 UTC。

Hall/Seat 独立 version 创建为0；真实改名或状态变更+1；同版本同目标状态（或同名）不写入、不递增。先查版本，旧版本即使目标相同也冲突；达到 INT 上限拒绝真实变更，不溢出。schedule_version 不作为编辑版本、不随维护变化。

创建 Hall 与非空初始座位同事务，均 ENABLED。追加要求厅启用且不存在任何关联场次（含历史/CLOSED/ENDED）。有未来场次也允许停 Hall，不取消订单、不重写场次；停用厅禁止布局查询及座位追加/启停，但厅详情、改名及重新启用允许。重启不主动重开场次。

Seat 停用只检查未关闭、未结束且 endTime 严格晚于锁后影院时间的场次；仍有 HELD 或 SOLD 即拒绝，已到期但尚未释放的 HELD 仍阻止。不查询订单到期、不清占座、不修改 screening_seat。允许全部物理座位停用。恢复只恢复独立物理条件，不解除场次 DISABLED、不补入未初始化席位。

## 锁、销售联动与上线限制

管理写事务 READ_COMMITTED：HallMapper.lockRow 的 Hall 主键 FOR UPDATE → Seat 主键 FOR UPDATE（需要时）。HallScheduleGuard.lockHallRows 提供去重、升序、Hall-only 原语；排片 lockHalls 在相同 Hall 行锁之后才锁场次。因此追加与首个排片、停厅与排片互斥，不把维护锁扩大到订单。管理查询和停用占售判断均普通非锁定读，不清过期；保留用户选座 GET 的旧过期兜底。

公开场次座位只返回本厅启用物理 Seat 且场次席位非 DISABLED；人工/自动开售最终 AVAILABLE 计数与自动候选同时要求物理 Seat 启用、归属本厅、order_id为空。恢复物理座位不补场次席位。场次创建保留201 + id-only + Location。

**当前工作区订单侧已接核心锁，真实竞争未验收。** `OrderServiceImpl.create` 调用 `OrderWorkflowMapper.lockPurchase`，按 Hall→Screening 主键行锁重读厅归属，再在购票校验中检查物理 Seat 启用与同厅；其 Hall 锁与维护/排片竞争同一数据库记录。这是并行订单模块的实现，不归功于 Hall，也不代表维护—下单—支付—过期—退款完整闭环已验收。需在授权真实 MySQL 中验证锁足迹、竞争结果及死锁风险；此次不修改订单、支付、退款或认证文件，不扩张过期重构。

只记录的订单侧残余风险：`OrderWorkflowMapper.insert` 的 AVAILABLE→HELD 条件更新尚未要求 `order_id IS NULL`，购票校验也未显式验证该字段；异常的 AVAILABLE+非空归属可能被覆盖。Hall 侧可售计数已要求空归属，但不能替代订单写入校验，后续由订单负责者处理并验收。

迁移文件为 V29__hall_seat_edit_versions.sql（V28已被认证占用），只新增版本列及非负约束；未执行真实库DDL。发布前核对 history/checksum、引擎/collation、异常关联和存量，备份并在授权隔离库验收；多条MySQL DDL非整体事务，失败不得自动repair。

条件真实库用例复用 ScreeningManagementMySqlTests，只有显式 flyway.poc.enabled=true 才会执行，并要求独立安全前缀隔离库和环境变量密码；普通 verify 跳过，不代表事务、隐式锁足迹或无死锁验证通过。订单/支付/过期/退款的真实竞争仍是上线风险，不扩展过期重构。
