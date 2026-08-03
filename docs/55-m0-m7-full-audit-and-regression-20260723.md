# M0–M7 全面审查与回归门禁

> 审查日期：2026-07-23  
> 状态：已完成；M0–M7 当前代码、真实中间件、故障、多实例、容量和质量门禁全部收敛，M8 准入  
> 目标：在进入 M8 前，对 M0–M7 的源码、契约、依赖、脚本、文档、自动化测试和真实关键闭环重新建立当前代码状态下的证据

## 1. 门禁原则

- 项目根目录只使用 `C:\Users\lenovo\Desktop\PlainJournal`。
- 现有未提交修改和新增文件均视为用户成果，不执行 `reset`、`checkout`、`clean` 或覆盖。
- MySQL 是订单、库存、资金和权益的最终事实；Redis 不承担最终库存。
- 中间件异常不得伪造成功，允许处理中或结果未知，并通过查询、重试、补偿和对账恢复。
- Docker、网络、真实中间件和多实例实验严格串行，使用互斥 Profile，以时间换空间。
- Docker 操作前遵守 `07-local-development-network.md`，不修改网卡、路由、跃点、代理、Docker 数据或全局镜像源。
- 自动化测试、H2 和浏览器夹具不能替代真实 MySQL、Redis、Nacos、RocketMQ、MinIO、多实例和故障证据。

## 2. 工作树、目录与死代码审查

最终工作树包含 384 项，其中 176 个已跟踪修改、208 个未跟踪项。所有内容均在原工作树上审查，没有清理、覆盖或写入已删除的旧项目目录。

源文件审查结果：

- 排除 `.git`、`target`、`node_modules`、`dist`、`.run`、Playwright 报告后，共审查 861 个源码、配置、脚本和文档文件；
- 空文件 0；
- 主代码未发现独立 `TODO`、`FIXME`、`HACK`、`System.out`、`System.err` 或 `printStackTrace`；
- 大小写不敏感扫描命中的 6 个 `toDomain` 已用精确规则复核，不是 TODO；
- 精确内容重复只涉及两端小型入口配置、Catalog 只读适配器和服务测试占位配置；这些文件属于独立应用或测试启动边界，当前抽取会增加耦合；
- 没有发现能够在不改变领域边界、框架装配或真实验证工具的前提下机械删除的生产代码或依赖。

`backend/.run` 约 1.09 GB、13,007 个文件，主要空间来自 M3 双版本实验源码快照。M3–M7 正式 JSON、日志和验证目录均被专题文档引用，本轮不以“清理磁盘”为理由删除证据。

## 3. 高危边界审查与修复

### 3.1 同步响应身份

Trade 的同步客户端不再只根据 HTTP 成功状态继续推进，而是校验返回事实是否属于当前命令：

- Catalog 返回的 `productId`；
- Identity 返回的 `addressId`；
- Inventory 返回的仓库 code 和预占单号；
- Payment 返回的 `orderNo`；
- Marketing 返回的 `orderNo` 和 `userId`。

上游返回错误身份时按同步边界失败处理，不把别的订单、地址、商品、用户或预占事实误认为本次成功。

### 3.2 取消、消息和直连配置

- Trade 在完成取消前核对 Inventory 返回的预占单号、订单号、仓库和 SKU 行；任何错配都保持 `CANCELING`，不提前写成 `CANCELED`。
- 早期消费者补充 `payloadVersion == 1` 校验；Payment 与 Fulfillment 消费者不再把缺失 `userId` 默认为 `0`。
- 无法识别的事件版本或缺失身份进入有限失败与消费失败治理，不伪造领域成功。
- `service-discovery-enabled=false` 时，Marketing 与其他同步依赖一样使用直连 WebClient Builder，并显式配置 `marketing-base-url`。

### 3.3 高并发与分布式正确性

- Trade Outbox 分片 Hint 在事务开始前建立；Claim、租约、所有者围栏和同聚合前驱约束保持一致。
- Inventory 最终裁决继续依赖 MySQL 条件更新、唯一约束、状态机和幂等，不依赖 Redis 最终库存。
- Payment 退款派发丢失租约时不会写成成功；渠道回调仍是最终事实。
- Gateway 在 Redis 故障时降级为有界本地限流，不放开无限流量。
- 前端写操作使用稳定幂等键、待确认事实、权威查询恢复和跨账户隔离。

这些结论来自源码、状态机、SQL、唯一约束、自动化和真实故障共同审查，不只依赖日志。

## 4. M0–M7 真实回归

### 4.1 M0–M2：完整交易、观测、追踪、韧性和 1000 并发量级

最终成功运行：

```text
backend/.run/m0-m7-foundation-20260723-r3.stdout.log
backend/.run/capacity-baseline.json
backend/.run/inventory-reservation-response-loss.json
```

结果：

- Gateway 与七个业务服务健康，Nacos 路由、请求 ID 和全部 Flyway schema 通过；
- 注册登录、地址、商品、MinIO、营销锁价、库存预占、订单、支付、履约、整单售后、退货回补和退款全链通过；
- Payment、Inventory、Trade、Fulfillment 四个所有者域对账检测与恢复通过；
- Inventory 1000 请求/100 并发严格得到 100 个预占、900 个拒绝；
- Trade 1000 请求/100 并发严格得到 100 个初始可支付订单、900 个缺货关闭；
- 同订单键、同支付回调、同退款回调各 100 路并发只形成一份有效事实；
- Prometheus 配置与 16 条规则、5 个实时采集目标、Alertmanager、Grafana、Tempo 和两条跨 HTTP/Outbox/RocketMQ 的代表 trace 通过；
- Payment→Trade、Trade→Marketing 的有限重试、熔断、半开恢复、舱壁与零脏写通过；
- Redis 故障时 Gateway/Identity 使用有界本地降级；
- Inventory 在 MySQL 已提交后丢失 HTTP 响应，Trade 使用原预占号查询权威事实恢复，未产生重复预占。

首次尝试曾被停电前遗留的 Trade worker 租约阻断；释放遗留进程并等待租约安全窗后，完整运行重新执行并通过。失败证据保留，最终结论只取成功重跑。

### 4.2 M3：多实例、抢占、进程终止和发布治理

| 能力 | 最终证据 | 结果 |
| --- | --- | --- |
| Outbox 1/2/3 实例 | `backend/.run/trade-outbox-multi-instance.json` | 每档 1000 事件全部发布；三实例 370.63 events/s；过期 owner 2.30 秒恢复 |
| Trade 容器 1/2/3 实例 | `backend/.run/trade-container-multi-instance.json` | 构建非 root 镜像、Nacos 注册、三实例共同发布和优雅停止通过 |
| 消费者竞争与终止点 | `backend/.run/trade-consumer-multi-instance.json` | 1/2/3 实例各确认 1000 条；重复投递幂等；三个进程终止点均恢复 |
| Gateway 滚动升级 | `backend/.run/gateway-rolling-upgrade.json` | 全阶段 HTTP/业务失败 0；失败候选未进入服务发现 |
| 双版本兼容 | `backend/.run/trade-dual-version-compatibility.json` | V5 稳定版与 V15 候选版双向事件兼容、回填和回滚通过 |

### 4.3 M4：前端真实交易边界

最终证据：

```text
backend/.run/m0-m7-m4-authoritative-20260723-r1.stdout.log
backend/.run/m0-m7-m4-payment-recovery-20260723-r1.stdout.log
backend/.run/m0-m7-m4-fulfillment-recovery-20260723-r1.stdout.log
```

- 权威结算使用购物车展示快照、Catalog 当前价格、Inventory 可用量和 Marketing 无副作用试算，只生成一份订单、预占和权益锁，取消后恢复；
- Payment 创建响应丢失后按原幂等键恢复唯一 `PROCESSING` 支付单，签名回调后 Payment、Trade、Inventory、Fulfillment 收敛；
- Fulfillment 确认收货响应丢失后查询恢复 `SIGNED`，历史 7 条、物流 3 条，Trade 最终 `COMPLETED`；
- 两条恢复链均验证跨账户 404 和 JavaScript 安全的字符串业务 ID。

前端最终门禁另覆盖主题、会话、购物袋、结算、订单、支付、履约、售后、管理端角色与对账工作区。

### 4.4 M5：容量、写链和缓存治理

最终证据：

```text
backend/.run/m0-m7-m5-query-20260723-r2/summary.json
backend/.run/m0-m7-m5-write-20260723-r2/summary.json
backend/.run/m0-m7-m5-cache-20260723-r2/summary.json
```

- Catalog/Trade 查询在 1、20、100 并发下每档各 1000 请求，错误均为 0；100 并发 P95 分别为 79.05 ms 和 90.60 ms；
- 购物车、结算试算、普通下单、支付回调和混合读写在 1、20、100 并发下均无传输或契约错误；
- 100 并发普通下单 1000 请求形成 1000 订单、1000 Marketing 锁和 1000 Inventory 预占，跨域数量一致；
- 100 路同订单键只形成一份订单/锁/预占；100 路同支付回调只形成一份有效支付事实；
- Inventory 1000/100 正确性门禁严格为 100 个 `RESERVED`、900 个 `REJECTED`；
- Catalog 热点、空值缓存、逻辑过期、两实例 Pub/Sub 失效、Redis 故障降级和重建背压全部通过；背压场景为 1 个权威 404、99 个容量保护 503；
- 确定性 M5 数据最终执行 Remove，未保留基线业务事实。

### 4.5 M6：秒杀准入、排队、恢复和普通流量隔离

最终证据：

```text
backend/.run/m0-m7-m6-admission-20260723-r1/summary.json
backend/.run/m0-m7-m6-queue-20260723-r2/summary.json
```

- 1000 请求/100 并发严格得到 100 个准入、900 个售罄；
- 一人一次、稳定令牌、Gateway 独立限流、Redis 故障失败关闭与恢复通过；
- Broker 故障期间 Marketing 接受事实和 Outbox 保留；恢复后 101 个接受事实全部收敛为 101 个订单；
- Inventory 最终为 `on_hand=101,reserved=101`，未发布、处理中、失败和需人工处理均为 0；
- 秒杀收敛期间普通订单、支付和退款查询混合 300 请求全部成功，错误率为 0。

### 4.6 M7：数据规模化、分布式 ID、读副本、分片、归档和重分片

| 批次 | 最终证据 | 关键结果 |
| --- | --- | --- |
| 规模数据与分页 | `backend/.run/m0-m7-m7-scale-query-20260723-r1/summary.json` | 10,000 SPU、20,000 SKU、50,000 订单；offset/keyset 各 100 行完全一致；API 600 请求错误 0 |
| 分布式 ID | `backend/.run/m7-distributed-id/verification.json` | 三 JVM 生成 3000 ID，碰撞 0；重复 worker 启动被拒绝；租约残留 0 |
| Catalog 读副本 | `backend/.run/m7-catalog-read-replica-20260723-124743/verification.json` | 暂停复制、主库 Hint、追平、副本停机回退和恢复通过；探针残留 0 |
| Trade 两分片 | `backend/.run/m7-trade-sharding-20260723-125013/verification.json` | `user_id % 2` 路由、跨片隔离、正逆向全链、11 组消费者和 4 个独立 Topic 通过 |
| 历史归档 | `backend/.run/m7-trade-archive-20260723-125855/verification.json` | 提交后中断续跑、幂等重跑、11 表指纹、篡改门禁、切读、回滚和重放通过 |
| 2→4 主动重分片 | `backend/.run/m7-trade-resharding-20260723-210422/verification.json` | NULL owner 失败关闭、在线变化、最终写栅栏、69 组指纹、篡改阻断、四片读取、受限回滚和重放通过 |

M7 证明的是单机缩比下的代表机制，不冒充生产无停机 CDC、自动故障转移或无限水平扩展。最终追平仍需要短维护写栅栏，目标产生新写后不能直接回滚。

## 5. 最终自动化与静态门禁

最终证据目录：

```text
backend/.run/m0-m7-final-quality-gates-20260723-r2
```

### 5.1 后端

```text
66 份 Surefire 报告
248 tests
0 failures
0 errors
0 skipped
11 个 Reactor 模块全部成功
```

模块分布：

| 模块 | 报告 | 测试 |
| --- | ---: | ---: |
| platform-common | 7 | 14 |
| Gateway | 5 | 11 |
| Identity | 3 | 7 |
| Catalog | 6 | 18 |
| Inventory | 6 | 23 |
| Trade | 22 | 104 |
| Payment | 6 | 36 |
| Fulfillment | 6 | 17 |
| Marketing | 5 | 18 |

静态分析：

- PMD Maven Plugin 3.28.0 / PMD 7.17.0：0 违规；
- SpotBugs Maven Plugin 4.9.8.2 / SpotBugs 4.9.8：9 份报告、208 条诊断；
- Priority 1 为 0、Priority 2 为 161、Priority 3 为 47、Missing Classes 为 0；
- CPD 的重复主要是 MQ 消费边界模板；没有在缺少共享故障测试时强行抽取共享基类；
- `mvn dependency:analyze` 的 Starter、自动配置、驱动、Flyway、Nacos、Actuator、Tracing、ShardingSphere SPI 和测试聚合项均按框架装配误报复核，没有可直接删除的生产依赖。

### 5.2 Outbox 测试时序竞态

首次最终 `mvn clean verify` 的唯一失败是：

```text
TradeFlowIntegrationTest.retriesTradeOutboxPublicationWithoutLosingEvents
expected PUBLISHED=1, actual=0
```

根因不是业务 Outbox 丢失，而是测试时钟与数据库 `TIMESTAMP(3)` 的毫秒精度竞态：JVM 纳秒时间落库时可能进位到下一毫秒，零延迟立即重试时查询时刻可能仍早于 `next_attempt_at`。

修复：

- 测试改用毫秒对齐、可推进时钟和 1 秒重试窗口；
- 明确验证窗口前不发布、到点后发布；
- 同时验证 `status`、`attempts`、`next_attempt_at`、`claim_owner`、`claim_until` 和 `last_error`；
- `OutboxPublisherJob` 实现 `AutoCloseable`，测试在 `finally` 关闭线程池。

修复后该定向用例累计 5 次通过，Trade 全套 104 项通过，第二轮全量 `mvn clean verify` 248 项通过。

### 5.3 前端、依赖、脚本与文档

- Node 唯一入口：`D:\Node.js\current\node.exe` 24.14.0；
- pnpm 11.9.0，`NODE_HOME=D:\Node.js\current`，`NODEJS_HOME` 未设置；
- `pnpm check`：Foundation 18、Storefront 50、Admin 2，共 70 个 Vitest；2 个 Playwright E2E；类型检查、生产构建和 axe 均通过；
- `pnpm audit`：已知漏洞 0；
- 33 个 PowerShell 脚本 Parser 错误 0；
- 仓库内排除生成目录后共 63 个 Markdown，相对链接断链 0；
- Compose 的 `core`、`m3-gateway`、`m3-trade`、`m7-catalog-replica`、`m7-trade-sharding`、`observability` 六个 Profile 全部通过配置展开；
- `git diff --check` 通过，仅有既有 LF/CRLF 提示；
- 无构建产物被 Git 跟踪。

## 6. 最终资源残留与停电后复核

停电恢复后重新执行只读审计：

- Docker 引擎可用；
- MySQL、Redis、Nacos、RocketMQ NameServer/Broker/Proxy、MinIO 七个核心容器运行；
- M7 临时 schema 和数据库账号匹配为 0；
- Redis 中 M7/reshard/archive/replica/shard 临时键为 0；
- RocketMQ 持久化 Topic、消费组和 Offset 配置中无 `m7-trade-*`、Retry 或 DLQ 残留；
- 临时容器、Docker volume、网络和 MySQL `/tmp/m7-*` 文件为 0；
- PlainJournal 业务 Java 进程为 0；
- 18000、18101–18107、18144、18204、18214、18224 等业务/实验端口均未监听；
- Catalog 副本、Trade 第二 MySQL 和观测栈均恢复到实验前状态；观测容器保留为停止状态，不误删其配置与历史证据。

`docs/07-local-development-network.md` 未在本轮修改。机器级网络脚本已按名称验证七个必需容器，不再断言运行容器总数恰好为六或七。

## 7. 最终结论与 M8 准入

M0–M7 在当前工作树状态下已经完成：

- 全目录与高危边界人工审查；
- 后端、前端、依赖、脚本、Compose 和文档门禁；
- 真实正逆向交易、一致性、补偿、对账、观测、追踪与同步韧性；
- 1000 请求/100 并发正确性；
- 1/2/3 实例、进程终止、滚动升级和双版本；
- 前端结果未知恢复；
- 普通容量、缓存、秒杀、数据规模化、读副本、分片、归档和重分片；
- 临时资源与端口最终清理。

没有发现阻断下一阶段的 P0/P1 缺陷，M8 准入。这里的“准入”不等于 M8 已完成，也不把单机结果外推为生产 SLO、生产高可用或无停机迁移承诺。

M8 应继续遵守单机现实边界：以时间换空间、互斥 Profile、单批小闭环和真实证据推进；如果某项理想架构需要多机、独立网络或更大内存，只保留接口、机制和验证设计，等真实环境覆盖本机缩比证据，不反向阉割 M0–M7 已经成立的能力。
