# 技术采纳矩阵与单机实验边界

## 1. 决策原则

素简记的目标不是收集中间件，而是在个人单机条件下把分布式问题做清楚：

> 能力完整、组件克制、规模缩比、证据真实。

一项能力进入主线前必须拥有真实业务载体、可复现失败、自动验证和回退边界。同一能力默认只保留一种主线实现；替代框架只在能够证明不同机制时进入独立 POC。

## 2. 运行规模

```text
常规开发：当前十一个应用按需各运行一个实例

交易竞争：Trade × 3，Inventory × 3，其余 × 1
消息竞争：Payment × 3，Fulfillment × 3，其余 × 1
发布治理：Gateway × 3，代表业务服务 × 3，其余 × 1

M7 查询实验：核心中间件 + Gateway/Identity/Catalog/Trade，结束后释放 JVM
M8 附件扫描：核心中间件 + Gateway/Chat + 按需 ClamAV，结束后恢复初始状态
M8 通知投递：核心中间件 + Gateway/Payment/Notification + 本地 SMTP 捕获器
M8 物流 GEO：MySQL + Redis + 单个 Fulfillment JVM，真实空间查询后清理夹具
M8 商品搜索：七个核心中间件 + 单个 Catalog JVM + 按需 OpenSearch
M8 运营统计：七个核心中间件 + Gateway + 单个 Analytics JVM，临时 Topic/schema
重型实验：MySQL 读副本、分片、完整观测栈、ClamAV、OpenSearch 按 Profile 互斥启动
```

三个同服务实例足以验证注册发现、负载均衡、无状态认证、Outbox 抢占、消费者组竞争、定时任务租约、实例退出和滚动升级。项目不把十一个应用同时扩为三实例作为完成条件。

## 3. 分布式微服务底座

| 能力 | 决策 | 验证标准 |
| --- | --- | --- |
| Nacos Discovery / Config | 主线保留 | 三实例注册、实例退出、配置刷新、配置不可用启动策略 |
| Spring Cloud Gateway | 主线保留 | 路由、认证、限流、灰度头、请求 ID 和失败回退 |
| 同步 HTTP 客户端 | 使用 Spring `RestClient`/HTTP Service Clients | Payment→Trade 只读查询与 Trade→Marketing 幂等命令两个代表边界已通过超时预算、服务发现、熔断、舱壁和有限重试；其余边界逐批验证 |
| OpenFeign | 不进入主线 | 官方已转为 feature-complete；仅维护遗留项目时学习 |
| Resilience4j | 主线保留 | Payment→Trade 与 Trade→Marketing 两个不同语义的代表边界已完成熔断、并发舱壁、拒绝指标和真实停服恢复；新增边界仍按风险逐项准入 |
| Sentinel | 条件 POC | 只有动态热点参数或集群流控产生独立学习价值时引入 |
| 灰度、蓝绿、流量染色 | 主线保留 | Gateway + Nacos 元数据 + 三实例完成，不以前置 Kubernetes 为条件 |
| 分布式追踪 | Micrometer Tracing + OpenTelemetry bridge + Tempo 主线 | 支付成功和退款成功两条 Payment HTTP→Outbox→RocketMQ→Trade 链路已通过 W3C 上下文和真实 Tempo 查询；继续按风险覆盖其余 HTTP、消息与任务 |
| Zipkin / SkyWalking / OTel Collector | 当前不引入 | 保持单一 Tempo 后端与单一 OTel bridge，不双重埋点；只有现有拓扑不能满足明确需求时再独立评估 |
| 分布式 ID | 主线代表实现已落地 | 41/10/12 位生成器接入普通/秒杀订单；MySQL worker 租约、时钟回拨、租约安全窗和节点冲突均失败关闭；三 JVM 共 3,000 个 ID 无碰撞 |
| Redisson 锁 | 限定使用 | 仅缓存重建和非最终事实协调；锁丢失不能破坏业务事实 |
| Redlock | 舍弃 | 不为个人环境常驻五个独立 Redis Master，不作为库存/资金裁决 |
| Maven 聚合与版本管理 | 主线保留 | 根工程单命令构建、依赖版本集中管理 |
| Docker / Compose | 主线保留 | 最小镜像、健康检查、Profile、清理和可重复启动 |
| WSL2 / VMware 网络 | 环境文档 | 诊断真实拓扑，不把盲目调跃点数包装成架构能力 |

参考：Spring 官方将 OpenFeign 视为 feature-complete 并建议迁移到 HTTP Service Clients；Spring Cloud CircuitBreaker 提供 Resilience4j 实现；Redis 官方 Redlock 描述依赖多个相互独立 Master 和多数派。选型以项目版本矩阵和真实 POC 为最终依据。

## 4. 最终一致性

以下全部属于主线能力：本地事务 + Outbox、RocketMQ、幂等消费、稳定业务号、重复与乱序处理、有限重试、毒丸隔离、补偿、对账、正向与逆向状态机，以及订单、商品、价格、地址、优惠和退款分摊快照。

不采用 2PC 跨库大事务。超时只表示结果未知；中间件不可用时保持处理中并恢复，不能伪造成功。

Notification 继续复用这条主线，不新增独立工作流引擎：来源事件幂等、通知任务、
站内信和邮件任务在同一 MySQL 本地事务落库；邮件通过数据库租约在事务外调用 SMTP。
SMTP 失败有限重试，超限进入人工关注；管理员只能用幂等审计命令重新进入 `RETRY`，
不能直接制造 `SENT`。稳定 `Message-ID` 降低外部重复风险，但不把 SMTP 描述为
exactly-once。

Analytics 同样复用本地事务、RocketMQ、幂等消费、消费失败治理、对账和审计补偿：
来源事件日志与日/商品汇总在一个本地事务提交，Broker 故障时不 ACK；逻辑身份冲突
进入失败治理；重建只从本服务事件日志计算，并通过投影锁和幂等审计命令收敛。它不
跨服务 JOIN，也不把旧事件缺失的商品收入进行估算。

## 5. 高并发与数据规模

| 能力 | 边界 |
| --- | --- |
| Caffeine + Redis | 只在 Catalog 热点查询等代表场景实现多级缓存 |
| 穿透/击穿/雪崩 | 空值、随机 TTL、互斥重建、逻辑过期和降级均需测试 |
| Redis Lua | M6 已用于秒杀固定配额、一人一次、稳定令牌和 Gateway 限流；只做入口准入，不保存最终库存 |
| 库存预占 | 普通与秒杀订单统一由 MySQL 原子条件更新、唯一约束和流水做最终裁决 |
| MySQL 一主多从 | Catalog 代表实现已完成：公开 GET 显式路由副本，写事务/Flyway 固定主库，主库提示处理读己之写，连接故障最多回退一次 |
| ShardingSphere-JDBC | Trade 有限代表实现已完成：5.5.3、`user_id % 2` 两片业务闭环、逐片 Outbox/对账、历史归档，以及受控 2→4 重分片、最终写栅栏、69 组全列指纹、四片读取和受限回滚 |
| 大表分页 | Catalog 与 Trade 保留 offset 兼容接口并新增 keyset 游标；Small 已验证深页结果不重不漏、执行计划和 600 个 Gateway 请求 |
| 分层限流 | Gateway 独立活动策略与 Marketing Lua 门闩已落地；Redis 健康时 Gateway 只以 Redis 为准，本地窗口只在 Redis 故障时启用 |
| MQ 削峰与隔离 | M6 已使用独立 Topic、消费组、Outbox 发布器、Trade 调度池和容量水位，并验证 MQ 停机积压与恢复 |
| MySQL Spatial + Redis GEO | Fulfillment 使用 `POINT SRID 4326`、空间索引和 `ST_Distance_Sphere` 裁决有界附近查询；Redis GEO 只保存可重建最新位置加速，缺失或异常时回退 MySQL，不承担物流事实 |
| OpenSearch 商品搜索 | Catalog MySQL 是最终事实；同库搜索 Outbox、外部版本、蓝绿别名切换、对账修复和明确 MySQL 降级已进入主线。按需单节点 512 MiB 堆，不常驻 |
| 压测 | 已完成 M2/M3/M5 的 1000 请求/100 并发门禁；M6 完成 1000/100 秒杀准入与 300/30 混合峰值；M7 Small 完成 1 万 SPU、5 万订单、10 万订单行下 Catalog/Trade 600 请求、20 并发的 offset/keyset/点查对比 |

M7 分布式 ID 只作为有限领域代表实现：当前仅替换 `trade_order.id`，内部快照和历史行不为统一形式机械迁移。worker 唯一性由 MySQL 租约裁决，Redis 不参与最终正确性；真实证据见 [M7 第二批：分布式 ID 与节点租约](50-m7-distributed-id.md)。

M7 Catalog 读副本同样只作为有限代表实现。单机 Profile 复制 `ecom_catalog`，
缓存专项关闭，暂停复制时不把旧值伪装成最新值；主库提示、连接故障回退和恢复
均有指标与真实证据。本机为避免重启现有主库使用 binlog 文件/位置，生产目标
仍是 GTID、复制 TLS 和长期副本监控。见
[M7 第三批：Catalog 真实 MySQL 读副本](51-m7-catalog-read-replica.md)。

M7 Trade 分片仍是有限代表实现。两个物理 schema 使用相同 Flyway 版本，顾客
业务按 `user_id % 2` 单片执行，跨服务事件携带 `userId`，Outbox 和对账逐片
使用本地事务。无分片键仅允许后台受控只读广播；Redis 不保存路由事实，且不
使用 2PC。真实正逆向交易证据和清理结果见
[M7 第四批：Trade 两分片代表实现](52-m7-trade-sharding.md)。

M7 历史归档同样按有限代表实现处理。两个分片分别使用本地批事务、固定截止点、
稳定订单 ID 游标、checkpoint、批次审计和 manifest；非终态售后、待发布
Outbox 与开放对账问题均阻止归档。11 表源目标指纹、篡改拦截、切读门禁、
只删除归档副本的回滚和回滚后重放已在两个真实 MySQL 上通过，见
[M7 第五批：Trade 历史归档迁移、校验与回滚](53-m7-trade-history-archive-migration.md)。
归档工具本身不冒充主动分片扩容；后续第六批已经补齐受控
`user_id % 2 -> user_id % 4` 代表闭环。`consumed_event.owner_user_id` 保存消费事实
的稳定用户归属，旧 NULL 行必须人工治理；迁移使用断点批复制、最终写栅栏、69 组
全列指纹、篡改门禁、四片读取和显式 `-ConfirmNoTargetWrites` 回滚。单机复用两个
真实 MySQL 和四个 schema，且没有反向复制，因此不描述为生产无停机 CDC。见
[M7 第六批：Trade 主动 2→4 重分片](54-m7-trade-active-resharding.md)。

## 6. 中间件与平台化

- RocketMQ 5.x、Redis、MySQL、MyBatis-Plus、MinIO、JWT/RBAC 和支付/退款渠道适配属于主线。
- ClamAV 是 M8 附件安全的按需代表实现：只负责隔离对象的流式扫描，不保存业务
  最终事实；不可用时附件失败关闭并有限重试，超限进入人工关注。它使用独立 Profile
  和 3 GiB 上限，不进入七个核心中间件常驻基线，也不扩张为多套扫描引擎。
- Spring Mail/SMTP 是 M8 Notification 的单一邮件主线。开发验证使用最小本地 SMTP
  捕获器，不常驻新增邮件容器；真实供应商接入前仍需补充 TLS、凭据托管、退信、
  投诉、限额和供应商幂等能力，不以本地捕获器冒充生产送达。
- Redis GEO 是 M8 Fulfillment 的可重建读加速，不是另一套物流数据库。全部轨迹和
  最新位置投影由 MySQL 持有；附近查询固定由 MySQL Spatial 裁决。当前前端展示坐标
  事实，不引入外部地图 SDK、路线规划或实时 GPS。
- OpenSearch 是 M8 Catalog 的单一全文搜索投影，不是商品主库。Catalog 事务只写
  MySQL 事实与同库 Outbox；索引可以全量重建，故障时显式退化，版本对账只能生成
  修复 Outbox。`m8-search` Profile 使用单节点、512 MiB 堆和 1408 MiB 容器上限，
  不与其他重型 Profile 常驻叠加。
- Analytics Java 服务是当前自营 B2C 运营统计主线，只消费版本化事件并持有可重建
  读模型。M9 的 Go 服务用于三个商户阶段的异构实现练习，不提前替代 Java 统计，也
  不参与订单、支付、库存或结算裁决。
- Spring Scheduled + 数据库租约是当前调度主线；XXL-Job 只有在补偿和对账需要集中调度、审计与人工触发时再做 POC。
- Prometheus + Alertmanager + Grafana 是指标主线，Tempo 是追踪主后端；四者只通过独立 profile 按需启动。指标采集使用独立 secret，不匿名开放 Actuator，也不复用管理员登录令牌；追踪导出默认关闭，由真实验证显式开启。
- DDD 只用于领域边界、聚合、状态机和语言统一，不建设无业务价值的形式层。
- 多商户放在自营主线、观测、三实例、压测和前端主流程完成之后，固定三个商户实现可验证的最小垂直切片：商户、店铺、租户权限、子订单、分账和结算。Go 只承接可重建的异构统计读模型，不参与 Java 主交易事实裁决。
- 共享表租户隔离与独立库隔离是不同演进方案，不在第一版同时实现。

## 7. 测试证据分层

```text
单元测试：允许 Mock，证明分支和算法
服务集成测试：H2/MySQL，证明事务、约束、状态机和权限
中间件 POC：证明客户端与真实版本兼容
领域真实冒烟：分别证明交易八应用、Chat、Notification、Fulfillment GEO、搜索与 Analytics 专项闭环
三实例故障测试：证明竞争、退出、恢复和滚动发布
压测报告：证明容量、拐点、错误率和业务正确性
```

任何“已完成”都必须指向相应层级的证据；不能用单元测试代替真实中间件，也不能用容器全部启动代替正确性验证。

M0–M8 当前最终门禁为 100 份 Surefire 报告、435 个后端测试，前端 141 个 Vitest、
14 个 Playwright E2E 与 12 条分层规则；12 份 PMD 报告为 0 违规，使用当前 Reactor
依赖重跑的 12 份 SpotBugs 低阈值报告 Priority 1 为 0、缺失分析类为 0。Analytics
另有真实 MySQL 8.4、Nacos、RocketMQ、Gateway 故障恢复、重复事件、收入覆盖边界、
三类偏差、审计重建、指标鉴权和清理证据；最新订单/Payment 三层证据见
[前端低耦合分层第七批](77-frontend-order-payment-layering-seventh-slice-20260730.md)。
H2 只验证事务、约束和状态机，不能代替真实 Broker 保留、Proxy 停机恢复或 Nacos
路由行为。详见
[M8 第十二批：运营统计事件读模型、对账与审计重建](67-m8-operational-analytics.md)。
