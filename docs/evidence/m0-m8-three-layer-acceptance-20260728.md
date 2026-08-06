# M0–M8 三层工程验收

> 审查日期：2026-07-28  
> 当前状态：M0–M8 三层工程复审完成；2026-08-01 产品决策已将原 M9 转入未来独立“素简记 Pro”  
> 适用范围：M0–M8 的业务、权限、分布式一致性、多实例、高并发、恢复与治理结论

本文件是最终三层验收快照，不再承担阶段路线图职责。当前仓库止于自营 B2C
M0-M8 参考基线，M9 及以后进入独立 PlainJournalPro。

## 1. 统一准入规则

本轮不再区分普通边界和高风险边界。每一个准备写入最终结论的能力都必须同时具备：

1. **代码证明**：明确所有者域、事务提交点、状态机、唯一约束、条件更新、租约或
   权限判定；代码只能证明设计和实现，不单独证明运行结果。
2. **自动化测试**：覆盖成功、拒绝、重复、并发和故障分支，并检查业务事实或数据库
   不变性；Mock 只可用于单元级分支，不替代跨服务结论。
3. **真实运行证据**：在当前代码构建物上，通过真实 HTTP 或浏览器网络、真实
   MySQL 事实和约束、Redis 键、RocketMQ Topic/位点、指标、端口与进程等独立事实，
   证明失败前后及最终收敛。

缺少任意一层时，状态一律为 `UNVERIFIED`，不得写成 `PASS`、完成或已毕业。

## 2. 日志边界

日志仅用于定位时间点、异常栈和候选关联 ID，不是正确性证明。以下内容不能单独作为
结论：

- “发送成功”“消费成功”“事务提交”等应用日志文本；
- 端口存活、健康检查为 `UP`；
- 单个 HTTP 200；
- 单个最终状态而没有前置事实、唯一性和数量核对；
- 历史运行日志对应的旧代码，而不是当前构建物。

真实层至少要保留两个相互独立的运行事实；所有链路优先使用 HTTP/浏览器抓包加直接
MySQL 查询，再用 RocketMQ、Redis 或指标补充时序。日志可以写入诊断目录，但不进入
通过判定公式。

## 3. 结论判定模板

| 结论 | 代码证明 | 自动化测试 | 真实运行证据 | 状态 |
| --- | --- | --- | --- | --- |
| 示例能力 | 文件、类、迁移与关键约束 | 测试类、数量、失败分支 | HTTP/F12、MySQL、MQ/Redis/指标 | 仅三列齐全时 `PASS` |

每一行必须描述同一个因果命题，不能用 A 能力的代码、B 能力的测试和 C 能力的运行结果
拼成一个“通过”。历史证据若早于当前相关代码修改，只能作为线索，必须在当前构建物上
重跑。

## 4. 当前构建物与证据根

所有真实证据在该次验收时收口到：

```text
backend/.run/m0-m8-pre-m9-audit-20260728-r6/evidence
```

每个 JSON 都记录运行条件、业务事实、清理结果和 `logsUsedAsProof=false` 或等价判定。
旧阶段报告用于解释演进过程，不覆盖本节当前结论。

## 5. M0–M8 最终三层矩阵

| 因果结论 | 代码证明 | 自动化测试 | 真实运行证据 | 状态 |
| --- | --- | --- | --- | --- |
| 服务只能写自己的数据库，关系凭据分域，正确性时间不依赖应用 JVM | 10 个 owner schema/user；生产 JDBC 强制 UTC 会话；`InternalClientProperties` 区分入站与出站关系密钥；Gateway 清除外部伪造内部凭据；Chat 在线租约取 Redis `TIME`；所有者应用业务代码无 JVM 时钟引用 | `GatewaySecurityConfigTest`、`InternalCredentialSanitizationGlobalFilterTest`、各服务应用测试、`DistributedIdGeneratorTest` 和配置静态门禁覆盖越权、凭据、时钟与唯一 owner 约束 | `boundary-config-final.json`、`database-time-contract.json`、`internal-trust-zones.json`：10/10/10 owner 域、28 条强制 UTC JDBC、共享密钥引用 0、全局数据库授权 0；错误域凭据均 401 且写入 0，正确链路收敛 | `PASS` |
| 同步 HTTP 不在本地事务中等待，超时/熔断/舱壁/有限重试只改变可用性，不伪造业务成功 | `SynchronousBoundaryGuard` 在 Trade→Marketing、Trade→Inventory、Payment→Trade 三个边界阻止事务内远程调用；Resilience4j 配置有界，响应身份与请求业务号绑定 | `SynchronousBoundaryGuardTest`、`TradeSynchronousBoundaryResilienceTest`、`HttpMarketingClientResilienceTest`、`HttpInventoryClientResponseLossTest`、`HttpTradeClientResilienceTest`、`HttpSynchronousResponseIdentityTest` 覆盖事务内拒绝、熔断、错响应身份与响应丢失 | `trade-marketing-synchronous-resilience.json`、`payment-trade-synchronous-resilience.json`、`inventory-reservation-response-loss.json`：停服时零提前副作用、断路器快速拒绝；恢复后按同一业务号查询，库存只有一条预占与流水 | `PASS` |
| 支付成功事实先保留，Inventory 权威确认前绝不生成 `OrderPaid` | `TradeOrderService.applyPaymentSucceeded` 的第一事务只写消费事实和 `PAYMENT_CONFIRMING`；事务外以原预占号确认/查询；第二事务只在 `CONFIRMED` 时写 `PAID + OrderPaid`，终态不可用进 `PAYMENT_EXCEPTION` | `TradeFlowIntegrationTest.keepsPaymentConfirmingWithoutOrderPaidUntilInventoryAuthoritativelyConfirms` 及重复、过期、晚到支付用例覆盖未知、恢复和终态分支 | `payment-inventory-causality.json`：Inventory 停服时 Payment `SUCCESS`、Trade `PAYMENT_CONFIRMING`、Inventory `RESERVED`、`OrderPaid=0`；重启后才出现 Inventory `CONFIRMED`、Trade `PAID`、`OrderPaid=1`、Marketing `REDEEMED`、Fulfillment `CREATED` | `PASS` |
| 取消后迟到支付不能反向履约，只有授权、幂等、审计退款可关闭异常 | Trade 对 `CANCELED/CLOSED` 的支付只写 `PAYMENT_EXCEPTION + PaymentReviewRequired`；Payment 以命令 ID、指纹、唯一退款和追加审计保护异常退款；新命令在事务外查询 Trade 权威状态 | `RefundFlowIntegrationTest` 与 `TradeFlowIntegrationTest` 覆盖顾客 403、并发命令、同键重放、变载荷冲突、Trade 不可用、失败/成功回调和 `OrderPaid=0` | `exceptional-payment-recovery.json`：取消后库存 `RELEASED`、权益 `AVAILABLE`；迟到支付后 `OrderPaid=0`、履约行 0；顾客 403/审计 0；并发 ADMIN 只有一笔退款；Trade 停服时旧命令可重放而新命令 503；回调后关闭且库存/权益不反转 | `PASS` |
| Fulfillment 异常恢复必须分层授权，并发只能有一个状态迁移 | `FulfillmentService.resolveException` 锁行，从异常历史恢复安全来源状态，以命令 ID/指纹幂等；审计、状态历史和 Outbox 同事务；Gateway 与服务都限制 ADMIN | `FulfillmentFlowIntegrationTest`、`GatewaySecurityConfigTest` 和前端 API 测试覆盖 WAREHOUSE 403、同键重放、变载荷冲突与并发命令 | `fulfillment-exception-recovery.json`：WAREHOUSE 403 且审计 0；两个 ADMIN 并发一个 200/一个 409；重放 200、变载荷 409；最终 `PICKING` 且审计/历史/Outbox 各 1 | `PASS` |
| 1000 请求/100 并发缩比下库存不超卖，同订单键、支付事件、退款事件各只有一个有效事实 | Inventory 条件更新与检查约束保证 `0 <= reserved <= on_hand`；预占号、Trade 用户幂等键、Payment/Refund 外部事件 ID 均有唯一约束和请求指纹；关键更新使用行锁/条件状态机 | `InventoryFlowIntegrationTest`、`TradeFlowIntegrationTest`、`PaymentFlowIntegrationTest`、`RefundFlowIntegrationTest` 覆盖竞争、同键、变载荷、重复回调和 Outbox 保留 | `capacity-baseline.json`：Inventory 100 `RESERVED`/900 `REJECTED`；Trade 100 可支付/900 关闭；三类同键各 100 次只有一个事实；传输错误 0，库存方程和幂等断言均真 | `PASS` |
| Outbox 与消费者在 1/2/3 实例、进程终止和租约过期下不重复业务副作用且最终排空 | Trade Outbox 使用 `claim_owner/claim_until`、`FOR UPDATE SKIP LOCKED`、聚合前驱和 ACK 后标记；消费者先写幂等事实，失败台账用数据库租约接管 | `OutboxClaimServiceTest`、`OutboxPublisherLeaseIntegrationTest`、`OutboxPublisherJobTest`、`ProcessTerminationFaultInjectorTest`、消费失败集成测试覆盖抢占、过期 owner、ACK 边界和三个终止点 | `trade-outbox-multi-instance.json`、`trade-container-multi-instance.json`、`trade-consumer-multi-instance.json`：各 1000 条在最多 3 实例下收敛，正式轮死锁、顺序违规、状态冲突和重复业务事实均为 0，终止后由其余实例接管 | `PASS` |
| HTTP/数据库可按受控顺序滚动，但当前 `PaymentSucceeded` 新旧消费者不能混跑 | 加法 Flyway 迁移保持旧 HTTP 读契约；Nacos promotion/drain 和 SIGTERM 顺序显式；当前 candidate 增加 `PAYMENT_CONFIRMING`/恢复任务，旧 stable 直接 `PAID/OrderPaid` | `TradePricingSnapshotMigrationTest`、Gateway 路由/安全测试、Trade 支付状态机和终止注入测试覆盖迁移、读取、退出和两套消费者语义 | `gateway-rolling-upgrade.json`、`trade-dual-version-compatibility.json`、`trade-dual-version-rolling.json`：入口 0 失败，坏候选未注册；V1 信封双向可读但工作流语义不等价，证据明确 `concurrentMixedConsumerRollingAllowed=false` | `PASS`，附发布限制 |
| M6 秒杀入口保护可以丢，最终名额和库存只由 MySQL 裁决；Broker 故障不丢已接受事实 | Marketing 活动/准入与 Outbox 同事务；Redis Lua 只做入口保护；Trade 同键建单，Inventory 条件更新最终裁决；结果事件幂等回写 | `FlashSaleFlowIntegrationTest`、`FlashSaleUnavailableIntegrationTest`、`FlashSaleOutboxLeaseIntegrationTest`、`FlashSaleOrderIntegrationTest`、`FlashSaleResultIntegrationTest` 覆盖 Redis/MQ 故障、同用户并发和结果重复 | `m6-flash-sale-queue.json`：1000/100 得 100 接受/900 售罄；MQ 停机后第 101 个接受事实保留，恢复后 101 个订单与 `on_hand=101,reserved=101` 精确收敛；混合 300/30 全成功 | `PASS` |
| M7 ID、读副本、两分片及 2→4 重分片在已声明的单机缩比边界内保持所有者与全列事实 | 41/10/12 位 ID、数据库租约/时间；Catalog 读路由可回主；Trade 明确 shard router；重分片有增量追赶、写栅栏、全列指纹和回滚限制 | `DistributedIdWorkerLeaseIntegrationTest`、`CatalogReadReplicaIntegrationTest`、`TradeShardingDataSourceIntegrationTest`、`HintTradeShardRouterTest`、`TradeReconciliationServiceShardRoutingTest` 覆盖租约、故障回主、跨片和所有者 | `m7-distributed-id-database-clock.json`、`m7-catalog-read-replica.json`、`m7-trade-sharding.json`、`m7-trade-resharding.json`：节点竞争、主从切换、跨 owner 404、提交后续跑、在线变化、最终栅栏、69 组全列指纹、篡改拦截和受限回滚均成立 | `PASS` |
| Identity 的 Redis 会话/限流异常不会把缓存当最终身份事实，也不会开放受保护操作 | Identity 以 MySQL 用户事实和 JWT/RBAC 裁决；Redis 只承担可降级的会话辅助/流量保护，异常路径明确失败或回退 | `AuthenticationFlowIntegrationTest`、`AddressFlowIntegrationTest`、Gateway rate-limit/security 测试覆盖认证、所有权、Redis 异常和拒绝 | `identity-redis-fallback.json`：真实 Redis 故障前后公开/受保护请求行为、MySQL 用户事实与恢复结果一致，未伪造认证成功 | `PASS` |
| 跨 HTTP 与 RocketMQ 的 trace 上下文可传播，但追踪后端不参与业务裁决 | Micrometer Tracing/OpenTelemetry 采用单一传播标准；生产者信封携带 trace，消费者创建 CONSUMER span；业务状态机不读取追踪结果 | `PaymentSucceededConsumerTracingTest`、`RefundResultConsumerTracingTest`、Request ID 与观测测试覆盖合法/非法 trace 和消费边界 | `distributed-tracing.json`：真实 Tempo 查询到支付与退款代表 trace，均包含 Payment/Trade 及 RocketMQ PRODUCER/CONSUMER span；业务 MySQL 与消息事实独立核对 | `PASS` |
| Chat 私聊正文按成员/认领权限隔离，实时消息到达后会话归属无需刷新即可与权威事实一致 | Chat MySQL 保存会话/成员/消息；客服认领前正文查询被拒；WebSocket 使用 Redis 单次票据；前端 `upsertConversation` 以版本防回退并在实时消息后查询权威会话 | `ChatFlowIntegrationTest`、`ChatRealtimeDeliveryIntegrationTest`、票据/失败治理测试和 Foundation 41 个 Vitest 覆盖认领、重放、响应丢失、旧版本响应与实时归属同步 | `m8-chat-browser-f12-r8.json`：认领前正文数 0，认领后可读/回复，顾客不刷新收到回复且显示“客服已接入”；23 个授权请求、7 个 WebSocket 101，页面/控制台/HTTP/网络错误及敏感泄露均为 0；MySQL 两条消息、两个 sender、Outbox 各一次，最终零残留 | `PASS` |
| Notification 重复事件、SMTP 故障和管理员恢复不会重复业务通知或伪造邮件成功 | 消费幂等、站内信、邮件任务同本地事务；邮件租约在事务外发送；有限重试到 `NEEDS_ATTENTION`，恢复命令需 ADMIN、命令 ID、原因和审计 | `NotificationFlowIntegrationTest`、`NotificationDomainEventConsumerTest`、`SmtpEmailSenderTest` 覆盖重复、故障、租约、越权和恢复 | `m8-notification-delivery.json`：真实 MQ/SMTP 故障与恢复、稳定 Message-ID、重复事实收敛、毒消息治理、权限与审计事实均核对，最终临时数据/组/端口/JVM 为 0 | `PASS` |
| Fulfillment 位置以 MySQL 空间事实为准，Redis GEO 可删除、暂停和重建 | 追加轨迹与最新位置同事务；`POINT SRID 4326`/空间索引裁决附近查询；Redis 投影提交后更新，缺失/异常回 MySQL，并按轨迹时间防旧事件覆盖 | `FulfillmentFlowIntegrationTest` 与 GEO 相关集成分支覆盖所有权、乱序、缓存异常与重建 | `m8-fulfillment-geo.json`：真实 MySQL Spatial 距离查询、南京/上海/乱序苏州、跨账户 404、Redis 删除和 `CLIENT PAUSE` 回主、管理员重建均通过 | `PASS` |
| 商品评价资格来自不可变已完成订单行，重复/并发提交与审核不破坏唯一资格和公开汇总 | Trade `OrderCompleted` 携带不可变行快照；Catalog 资格、评价、点赞、举报、回复与审核均有所有者/唯一约束，公开汇总只计 `PUBLISHED` | `ProductReviewFlowIntegrationTest`、`OrderCompletedConsumerTest` 覆盖并发幂等、变载荷、跨用户、隐藏与汇总 | `m8-product-reviews.json`：MQ 停机时 Trade Outbox 保留；恢复后资格收敛；真实 MySQL 下 8 路重试同一评价 ID，隐藏后公开汇总同步变化，最终零残留 | `PASS` |
| Catalog 搜索索引不是最终事实，故障时显式回 MySQL，重建/对账可修复 missing/stale/orphan | Catalog 事务内搜索 Outbox；OpenSearch `external_gte` 防旧事件覆盖；公开查询返回来源/降级标记；蓝绿索引+原子别名；管理员恢复有幂等审计 | `CatalogSearchFlowIntegrationTest`、`OpenSearchProductSearchIndexTest`、worker ID 配置测试覆盖停机、重试耗尽、重建和三类偏差 | `m8-catalog-search.json`：真实 OpenSearch 停机时 MySQL 写入继续且查询 `MYSQL_FALLBACK/degraded=true`；恢复、蓝绿重建、三类偏差注入/修复、下架隔离和清理均通过 | `PASS` |
| Analytics 只保存可重建事件读模型，重复事件不重复累计，身份冲突和偏差进入治理 | 来源事件日志以事件 ID/逻辑身份/指纹幂等；日/商品汇总同事务；重建以数据库租约、审计和来源事件重放，应用指标使用专用采集身份 | `AnalyticsFlowIntegrationTest`、`AnalyticsDomainEventConsumerTest` 覆盖重复、身份冲突、收入覆盖、租约、重建和偏差 | `m8-analytics.json`、`m8-analytics-cleanup.json`：真实 Broker 停机/恢复、六类事件、三类偏差、幂等审计重建、指标鉴权和迟建 `%RETRY%` Topic 清理均核对，临时资源为 0 | `PASS` |

## 6. 最终自动化与仓库门禁

| 门禁 | 当前结果 |
| --- | --- |
| 后端 `mvn clean verify` | 100 份 Surefire 报告，435 tests，0 failure/error/skipped |
| PMD | Maven Plugin 3.28.0 / PMD 7.17.0，12 份报告、0 违规 |
| SpotBugs | 安装当前 Reactor 产物后重跑低阈值扫描；12 份报告、313 条分类诊断，P1=0、P2=247、P3=66、缺失分析类=0 |
| 前端 `pnpm check` | Foundation 42 + Storefront 94 + Admin 12 = 148 Vitest；14 个 Playwright E2E；13 条分层规则；类型、构建、axe 均通过 |
| 脚本 | 49 个 PowerShell AST 0 错误；7 个 MJS `node --check` 0 错误 |
| 文档与工作树 | 85 个 Markdown、367 条相对链接 0 断链；`git diff --check` 通过；Git 索引内生成物 0 |
| Compose | `plainjournal` 项目名；8 组预期组合全部展开。`m3-gateway`、`m3-trade` 依赖 core，不宣称可单独展开 |
| 最终运行环境 | 当前 22 个业务/中间件端口监听为 0，PlainJournal JVM/Vite/代理为 0，Docker Desktop 与 Engine 均已停止；RocketMQ 数据目录无本批临时 run 引用 |
| 静态森林审查 | 生产代码无 `System.out/err/printStackTrace`，源码无待办标记；所有者业务服务无 JVM 时钟正确性引用；远程 HTTP 不在本地事务模板内；M9 前边界配置检查通过 10 个数据库所有者、10 个唯一用户和 0 个全局服务授权 |

SpotBugs 的 P2/P3 是分类诊断，不等同于“零问题”；本轮逐类审查后未发现 P1，也没有
把 PMD/SpotBugs 静态结果替代真实链路。阶段报告中的旧测试数字保留为历史快照，本轮
全域复审冻结点数字只以上表为准。

上述表格已经吸收 2026-07-30 前端主题与低耦合分层第七批的最新门禁，没有修改
后端业务语义。Catalog、会话、地址、购物袋、Checkout 和订单/Payment 均按同一
所有权规则逐条迁移；最新真实浏览器/F12 还验证支付创建上游 200 后响应丢失、原键
恢复、跨账户 404、取消/支付互斥，以及 Payment、Trade、Inventory、Fulfillment
四域数据库收敛。代码、自动化和真实运行矩阵见
前端分层契约测试。
该段记录的是当时冻结点；当前范围决策见本文开头的 2026-08-01 注记。

2026-07-30 继续完成 Fulfillment 前端分层第八批：真实浏览器让确认收货上游先
返回 200，再由本地代理丢弃 2863 字节响应；页面只在查询到 Fulfillment
`SIGNED` 后显示签收，并由刷新查询确认 Trade `COMPLETED`。本批同时验证中文
不可变快照、桌面/移动端无横向溢出、账户/令牌迟到响应隔离、并发命令合并和五域
零残留。

2026-08-01 完成售后前端分层第九批：Trade 售后、Fulfillment 退货和 Payment 退款
分别成为显式所有者 entity，workflow 只负责连续旅程。当前串行门禁为 162 个 Vitest、
14 个 Playwright E2E 和 14 条分层规则；内置浏览器验证寄回后保持
`RETURNING / RETURNING / PROCESSING`、桌面/移动端无溢出和零控制台错误。该浏览器
运行使用受控 HTTP 夹具，不替代本报告已经核对的真实逆向交易与资金证据；详见
前端售后三域分层测试。

2026-08-01 完成评价前端分层第十批：Catalog 评价事实成为单一 entity，订单评价
意图与商品参与动作分别位于两个 feature，页面只传入显式所有者访问上下文。当前
串行门禁为 173 个 Vitest、14 个 Playwright E2E 和 15 条分层规则；自动化让评价
POST 在上游完成后丢失响应，页面只按 Catalog 资格恢复且未发第二次 POST。内置浏览器
又完成订单评价、商品页回显、点赞和举报，桌面/移动端均只有一个 `main`、无横向
溢出且控制台零错误。该浏览器运行仍使用受控 HTTP 夹具，真实资格、并发唯一性与
MQ 恢复继续引用本报告和 `docs/65`；详见
前端评价分层测试。

## 7. 已确认的发布与能力边界

1. HTTP 路由、加法 schema 和健康候选可以按已验证顺序滚动；消息消费者必须另做
   语义兼容判定。当前 Trade `PaymentSucceeded` stable/candidate 禁止混跑。
2. candidate 消费工作流启用后若回滚，必须处理已经存在的 `PAYMENT_CONFIRMING`
   与恢复任务，不能直接把旧二进制重新加入消费组。
3. M7 重分片最终切换需要短写栅栏；目标产生新写后不能声称无条件回滚。这不是
   多机 CDC 或生产级无停机迁移。
4. 1000/100、最多 3 同服务实例和单机故障实验只证明当前 Y7000P 上的缩比机制，
   不外推生产吞吐、跨机房容灾或无限商户规模。
5. 原 M9 不在当前仓库实施；三个商户、平台资金所有权和 Go 服务转入未来独立“素简记 Pro”。
6. 最终 Broker 深查发现 4 个本轮临时订阅组只剩配置、没有 offset；已按精确名称删除。
   正式业务组、Topic 和 offset 保留，删除后再次核对临时三类资源均为 0。

## 8. 单机执行边界

- 重型中间件、全链、1000 量级容量、多实例和浏览器阶段串行执行。
- 同一服务多实例最多 3 个；使用时间换空间，不降低 M0–M8 已完成机制。
- 每个真实阶段结束后核对并清理本阶段的临时业务数据、消费组、端口和 JVM；七个核心
  中间件按后续阶段需要保留，不并行启动可选重型 Profile。
- 真实规模结论只适用于本机缩比实验，不外推为生产容量或多机容灾。

## 9. 最终判定

M0–M8 在当前代码和单机缩比范围内达到三层证据闭环。没有使用单个日志、HTTP 200、
端口存活或最终状态替代因果证明；资金、库存、权益、权限、消息竞争与发布边界均同时
具备代码、自动化和真实运行证据。

本报告在 2026-07-28 的原始结论是“允许用户开始复审”，不是“自动进入 M9”。
2026-08-01 用户已决定不在当前仓库实施 M9；这不推翻审查事实，只改变后续产品边界。
当前工作继续收敛前端主题、交互、演示与发布，不引入多商户领域模型。
