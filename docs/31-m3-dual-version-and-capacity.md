# M3 双版本兼容、滚动发布与容量复测

> 首次验证日期：2026-07-20  
> 当前代码复审日期：2026-07-28  
> 范围：M3 第五批、M3 整体收口及进入 M9 前的双版本语义复审  
> 环境：Windows 单机缩比、JDK 17、真实 MySQL 8.4.10、Redis、Nacos 3.2.2、RocketMQ 5.3.2 Proxy、MinIO；应用多实例实验最多三个实例

## 1. 结论

M3 已满足当前单机缩比范围内的退出条件，可以关闭：

- stable 与 candidate 使用真实不同二进制，而不是同一构建改标签；
- Trade 数据库从 Flyway V5 在线升级到当前 V20，候选版本迁移后旧版本仍可读取既有 HTTP 契约；
- Gateway/Nacos 完成 stable 双实例到 candidate 双实例的逐实例滚动替换，当前复审 1362 次请求 0 失败；
- 数据库启动失败的坏候选没有注册进 Nacos，健康 candidate 持续提供服务；
- `PaymentSucceeded` V1 的线级信封双向可解析，但旧 stable 与当前 candidate 的工作流语义不等价；新旧消费者禁止并行竞争，必须采用停旧、Broker 缓冲、只启 candidate 的切换方式；
- 最终候选版本完成 1000 请求、100 并发的 Inventory 与 Trade 竞争、同键幂等、完整正逆向交易及四域对账；
- M2 暴露的支付链 1018.326 秒长尾降至 71.887 秒，Trade 收敛时本轮 Outbox 未发布数由 106 降至 0；
- 本轮没有传输错误、超卖或重复业务副作用。

该结论只证明个人电脑上的机制正确性、恢复性和缩比容量，不代表生产硬件容量，不外推为八个服务全部三实例常驻，也不替代后续 M5 的容量拐点、缓存治理、背压和资源隔离实验。

## 2. 真实双版本矩阵

| 项目 | stable | candidate |
| --- | --- | --- |
| 来源 | Git HEAD `11b252515020794fff1870bebef1d0e0ac44155e` | 当前有意保留的工作树 |
| Trade 镜像 | `plainjournal/trade-service:m3-stable-20260728080356610` | `plainjournal/trade-service:m3-candidate-20260728080356610` |
| 镜像 ID | `sha256:10b78680...` | `sha256:30414f00...` |
| JAR SHA-256 | `98405bed9607...` | `1baaace246fb...` |
| 初始/最终 Schema | V5 | V20 |
| 发布标识 | `m3-stable` | `m3-candidate` |

镜像 ID 与 JAR 摘要不同，证明本轮验证的确是两个业务版本，不是同一二进制的伪滚动。

机器证据：

- `backend/.run/m0-m8-pre-m9-audit-20260728-r6/evidence/trade-dual-version-compatibility.json`
- `backend/.run/m0-m8-pre-m9-audit-20260728-r6/evidence/trade-dual-version-rolling.json`

## 3. 数据库迁移与 HTTP 兼容

当前复审使用独立 schema `ecom_trade_m3compat_20260728080356610`：

1. stable 在 Flyway V5 schema 上启动并读取一笔历史 `PENDING_PAYMENT` 订单；
2. candidate 启动并将 schema 从 V5 迁移到 V20；
3. V6 为历史订单项补齐不可变价格快照字段，验证值为 `line_no=1`、`payable_amount=39.80`；
4. candidate 读取历史订单的支付上下文，`orderNo`、`reservationNo`、状态、金额和支付截止时间与迁移前一致；
5. candidate 停止后重新启动 stable，stable 仍能在 V20 schema 上读取同一 HTTP 契约。

结果：

```text
Stable schema: V5
Candidate schema: V20
Legacy backfill: 1|39.80
stable before migration == candidate after migration == stable after migration
```

本轮证明的是向后兼容的加法迁移和历史回填。删除列、收紧非空约束、修改枚举语义等破坏性迁移仍必须遵循 expand/migrate/contract 分阶段发布，不能由这次结果推导为“任意旧版本都能读取任意新 schema”。

## 4. Gateway/Nacos 滚动与坏候选回滚

发布顺序沿用 M3 第三批已经验证的门禁：

```text
候选启动且健康
  -> Nacos promotion
  -> Gateway 观察新旧实例
  -> stable enabled=false 摘流
  -> 连续请求不再命中待停实例
  -> SIGTERM 优雅停止
  -> 替换下一实例
```

正式结果：

| 指标 | 结果 |
| --- | ---: |
| Gateway 请求 | 1362 |
| HTTP/业务失败 | 0 |
| 非预期 release | 0 |
| 初始 release | `m3-stable`，2 实例 |
| 最终 release | `m3-candidate`，2 实例 |
| 坏候选退出码 | 1 |
| 坏候选注册 Nacos | 否 |
| 回滚期间入口中断 | 否 |

坏候选使用错误数据库条件触发真实启动失败，并设置 `spring.cloud.nacos.discovery.register-enabled=false`。只有启动、迁移和健康检查通过的候选才允许进入服务发现，因此失败版本没有污染 Gateway 的实例缓存。

## 5. 双向线级兼容与工作流语义边界

当前复审使用 `PaymentSucceeded`、`payloadVersion=1`，先在没有消费者时证明 Broker
能够缓冲，再分别启动 candidate 与 stable 消费者：

| 生产者 / 消费者 | 消费前事实 | 消费后事实 |
| --- | --- | --- |
| stable / candidate | `PUBLISHED\|PENDING_PAYMENT\|0\|0\|0\|0` | `PUBLISHED\|PAYMENT_CONFIRMING\|1\|1\|0\|1` |
| candidate / stable | `PUBLISHED\|PENDING_PAYMENT\|0\|0\|0\|0` | `PUBLISHED\|PAID\|1\|1\|1\|0` |

六段状态依次记录 Payment Outbox、Trade 订单、支付状态历史、消费幂等事实、
`OrderPaid` Outbox 和支付确认恢复事实。当前 candidate 会先进入
`PAYMENT_CONFIRMING`，在事务外向 Inventory 取得权威裁决；旧 stable 则直接进入
`PAID` 并生成 `OrderPaid`。因此：

- JSON 信封和 V1 负载仍能被双方解析，`wireEnvelopeCompatible=true`；
- 业务工作流语义不等价，`workflowSemanticsEquivalent=false`；
- 不能让 stable/candidate 消费者同时竞争同一消费组，否则同一事件落到哪个版本会
  改变库存裁决顺序和下游副作用；
- 正确发布顺序是停止旧 `PaymentSucceeded` 消费者、由 RocketMQ 暂存事件、确认
  旧消费者全部退出后只启动 candidate；
- candidate 新工作流开始处理事件后，回滚到 stable 需要针对
  `PAYMENT_CONFIRMING` 和恢复任务执行显式恢复，不能把 HTTP 无损滚动结论外推到
  消息消费者。

本节修正了 2026-07-20 “双向最终均为 PAID，所以语义兼容”的过度结论。未来事件
演进除线级兼容测试外，还必须比较状态迁移、Outbox、副作用和恢复任务，不能只比较
反序列化成功或最终 HTTP 状态。

## 6. 最终 1000 请求、100 并发容量结果

正式命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\run-foundation-smoke.ps1 `
  -SkipNetworkPreflight `
  -EnableCapacityBaseline
```

最终机器证据：

- `backend/.run/capacity-baseline.json`
- `backend/.run/capacity-final-20260720222941.out.log`
- `backend/.run/capacity-final-20260720222941.err.log`

### 6.1 入口容量

| 场景 | 结果 | P50 | P95 | P99 | 吞吐 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Inventory 预占竞争 | 100 成功 / 900 拒绝 | 1139.75 ms | 1689.67 ms | 1774.42 ms | 74.97 req/s |
| Trade 下单竞争 | 100 初始可支付 / 900 缺货关闭 | 377.30 ms | 1290.69 ms | 1329.50 ms | 42.82 req/s |
| 同一订单键 100 次 | 1 套跨域事实 | 32.78 ms | 48.28 ms | 64.38 ms | 87.12 req/s |
| 同一支付回调 100 次 | 1 次有效结果 | 18.50 ms | 199.51 ms | 230.46 ms | 89.64 req/s |
| 同一退款回调 100 次 | 1 次有效结果 | 14.32 ms | 28.94 ms | 49.65 ms | 128.51 req/s |

正确性断言：

- `transportErrors=0`；
- 库存严格为 100 笔 `RESERVED`、900 笔 `REJECTED`；
- Trade 严格为 100 笔初始可支付、900 笔缺货关闭；
- `stockEquationVerified=true`；
- `idempotencyVerified=true`；
- 支付链收敛期间未付款预占过期数为 0；
- 完整正向、履约、售后、回补、退款以及 Payment、Inventory、Trade、Fulfillment 四域对账通过。

### 6.2 M2 基线对比

| 指标 | M2 基线 | M3 最终候选 | 结论 |
| --- | ---: | ---: | --- |
| 支付链收敛 | 1018.326 s | 71.887 s | 长尾显著下降 |
| Trade P95 | 2492.45 ms | 1290.69 ms | 明显改善 |
| Trade 吞吐 | 42.00 req/s | 42.82 req/s | 基本持平、小幅提高 |
| Trade 收敛时未发布 Outbox | 106 | 0 | 本轮完全排空 |
| 支付链收敛前过期的未付款预占 | 99 | 0 | 未越过 15 分钟业务截止时间 |
| Inventory P95 | 1341.90 ms | 1689.67 ms | 变差，保留为后续容量基线 |
| Inventory 吞吐 | 76.35 req/s | 74.97 req/s | 小幅下降 |

不能宣称所有延迟都改善。Inventory P95 上升到 1689.67 ms，说明入口竞争仍受单机数据库锁、线程调度和拒绝事件持久化成本影响；该结果应带入 M5 做 SQL、连接池、线程池和资源曲线分析，而不是继续扩大本批 Outbox 并行度掩盖入口瓶颈。

## 7. Inventory Outbox 饥饿根因与修复

容量场景会在 Inventory 产生约 2010 条 Outbox，其中包括大量库存拒绝事件。旧配置为：

```text
共享调度器
单线程逐条发布
batch-size = 50
fixed-delay = 2000 ms
```

`ReturnStocked` 虽然已经提交到 MySQL，但会被前面的容量事件按 FIFO 长时间阻塞，导致逆向链看起来停在仓库验收之后。该问题不是退款状态机错误，也不能通过放宽售后断言解决。

最终实现：

- 新增独立 `inventoryOutboxScheduler`，避免长轮询消费者和其他任务饿死 Outbox 扫描；
- 每次最多领取 200 条，调度周期 500 ms；
- 按 `aggregateType:aggregateId` 分组；
- 同一聚合在同一任务内串行发布，保持事件顺序；
- 不同聚合最多 8 路有限并行；
- broker ACK 成功后才标记 `PUBLISHED`；
- 失败保留 `PENDING` 与有限重试，不伪造成功；
- 应用关闭时最多等待 10 秒完成发布线程池收敛；
- 容量脚本记录支付链/退货链耗时及 Trade、Inventory Outbox 积压，失败时输出可定位诊断。

最终逆向链结果：

```text
returnChainConvergenceSeconds=10.922
inventoryOutboxUnpublishedAtReturnInspection=0
inventoryOutboxUnpublishedAtReturnChainConvergence=0
```

Inventory 当前的 claim 仍是当前单实例发布模型，不等价于已经复制 Trade 的多实例 owner/lease 围栏。M3 的多实例代表验证集中在 Trade；未来若扩展 Inventory 多实例发布，必须先补数据库 owner/lease、过期回收和进程终止证据。

## 8. 自动化与质量门禁

本批定向 Maven 测试组合共 16 个，0 失败、0 错误、0 跳过。新增回归明确覆盖默认调度器阻塞时 Inventory Outbox 仍可独立执行，以及发送失败保留待发布状态、后续重试成功和指标更新；最终容量冒烟继续验证完整正逆向链和 Outbox 排空。

最终全量门禁：

```text
PMD Maven Plugin 3.28.0 / PMD 7.17.0: PASS
mvn clean verify: BUILD SUCCESS
Surefire reports: 43
Tests: 145
Failures: 0
Errors: 0
Skipped: 0
完成时间：2026-07-20 22:41:27
```

## 9. M3 关闭边界与下一阶段

M3 已经形成以下完整证据链：

1. Trade Outbox owner/lease、`FOR UPDATE SKIP LOCKED`、同聚合顺序和过期回收；
2. Trade 非 root 容器、1/2/3 实例发现、真实 RocketMQ 发布和优雅停止；
3. 消费者竞争、1000 条重复投递幂等和三个 `halt(91)` 终止点；
4. Gateway/Nacos promotion、摘流、滚动替换和失败候选回退；
5. Inventory 预占已提交但 HTTP 响应丢失时的权威查询恢复；
6. stable/candidate 双版本数据库、HTTP、事件和发布兼容；
7. 最终候选版本的完整 1000 请求、100 并发容量复测。

按项目总计划，下一里程碑是 M4：Vue 3 顾客端与管理端 V1。M5 才进入普通业务容量优化、缓存治理、背压和资源隔离；M9 的三个商户与 Go 统计服务仍需等待前端主流程和后续容量/数据阶段完成。若要改变路线，应先明确调整计划书的里程碑进入条件，不能把多商户直接改名为 M3。
