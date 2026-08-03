# M2 毕业与容量准入报告

> 验证日期：2026-07-18  
> Git 基线：`11b252515020794fff1870bebef1d0e0ac44155e` 加当前有意保留的未提交工作树  
> 环境：Windows 单机、JDK 17、8 个 Java 单实例、真实 MySQL/Redis/Nacos/RocketMQ/MinIO，观测栈按需启动

## 1. 结论

M2 可观测性、同步韧性、补偿、分布式追踪和所有者域对账已满足退出条件，可以进入 M3。1000 请求容量准入证明库存与幂等正确性，但同时稳定暴露 Trade Outbox 消息推进长尾；因此这不是“性能已经完成”，而是为 M3 三实例、任务抢占/租约和故障恢复建立了可复现基线。

多商户和 Go 异构统计服务不属于当前 M3。它们保留在 M9，并限定为三个商户的最小垂直切片；进入条件仍是多实例、压测和前端主流程完成。

## 2. 只读审查与代码闸门

- 审查范围为 412 个 Java 文件及相关迁移、配置、脚本和文档。
- 主代码未发现 `TODO/FIXME`、`printStackTrace` 或 `System.out` 遗留。
- 未发现需要阻止真实验证的 P0/P1 安全、事务或一致性缺陷；少量 Locale、空值/算术异常与无用导入问题已修正。
- SpotBugs 诊断产生 171 条以 Spring 注入、record/数组暴露为主的告警；逐项抽查未发现应阻断本批的真实缺陷。该结果不作为“零问题”宣称。
- PMD Maven Plugin 3.28.0（PMD 7.17.0）全 Reactor `check` 通过，0 违规。
- JaCoCo 诊断覆盖率为行 70.1%、分支 45.7%；Fulfillment 最低，为行 59.2%、分支 29.6%。覆盖率是后续补测优先级证据，不以总体百分比替代关键状态机测试。
- 最终 `mvn clean verify`：39 份 Surefire 报告，130 个测试，0 失败、0 错误、0 跳过，11 个 Reactor 模块全部成功。

高并发审查还发现原 `INSERT IGNORE -> SELECT FOR UPDATE` 的同键竞争会让并发事务先持共享锁、再同时升级排他锁并形成真实 InnoDB 死锁。Trade、Inventory、Marketing、Payment 的幂等声明已改为 `INSERT ... ON DUPLICATE KEY UPDATE id=id` 后再锁定读取；目标模块与全量回归均通过。

## 3. Outbox 顺序与并行边界

Trade 容量场景一次产生约 2000 条事件。原同步逐条发布约每秒 2 条，关键 `OrderPaid` 会排在大量普通订单事件之后。当前单实例实现：

- 按 `aggregateType:aggregateId` 分组，同一聚合严格串行；
- 不同聚合最多 8 路并行；
- RocketMQ 使用原生异步发送，只有 broker ACK 成功后才更新 `PUBLISHED`；
- 测试证明不同聚合可并行、同一聚合 A1 必须先于 A2。

这仍不是多实例完成证明。M3 必须增加跨实例 claim/lease、过期租约恢复和进程终止点验证，确保并行吞吐提升不破坏同聚合顺序与至少一次投递语义。

## 4. 1000 请求容量证据

正式命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\run-foundation-smoke.ps1 -EnableCapacityBaseline -EnableDistributedTracing -EnableObservability
```

参数为每场景 1000 请求、100 并发，Inventory 与 Trade 分别只允许 100 个成功。证据写入忽略文件 `backend/.run/capacity-baseline.json`。

| 场景 | 结果 | P50 | P95 | P99 | 吞吐 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Inventory 预占竞争 | 100 成功 / 900 拒绝 | 1242.56 ms | 1341.90 ms | 1366.97 ms | 76.35 req/s |
| Trade 下单竞争 | 100 初始可支付 / 900 缺货关闭 | 2137.57 ms | 2492.45 ms | 2595.50 ms | 42.00 req/s |
| 同一订单键 100 次 | 1 套 Trade/Marketing/Inventory 事实 | 39.27 ms | 58.78 ms | 79.61 ms | 93.97 req/s |
| 同一支付回调 100 次 | 1 次有效支付结果 | 36.13 ms | 334.55 ms | 379.07 ms | 87.86 req/s |
| 同一退款回调 100 次 | 1 次有效退款结果 | 13.85 ms | 30.64 ms | 54.89 ms | 136.74 req/s |

正确性断言：

- 传输错误为 0，库存预占严格为 100/900，没有超卖；
- 同键订单、支付回调、退款回调均没有重复业务副作用；
- `available = onHand - reserved` 始终成立，确认只减少 `onHand/reserved`，退货只恢复 `onHand`；
- 关键正向订单、履约、退货、退款及营销权益全部完成；
- 99 个未付款订单在消息长尾期间按 15 分钟规则过期并释放预占，验收按 MySQL 实时预占状态核对，而不是把时间相关状态写成常量。

## 5. 明确的容量瓶颈

支付回调时本轮 Trade Outbox 尚有 1957 条未发布；支付链达到履约创建和营销核销条件耗时 1018.326 秒，届时本轮仍有 106 条普通事件未发布。入口 HTTP 延迟虽为秒级，异步推进尾部却达到约 17 分钟，导致 99 个未付款订单先到业务截止时间。

因此当前结论是“高并发正确性通过、异步尾延迟不达生产目标”。M3 不应先扩大商户模型，而应先回答：

1. 三实例发布任务能否安全抢占并显著降低 backlog age；
2. 多实例如何保持同聚合顺序，避免重复 claim 和重复副作用；
3. broker ACK 后、Outbox 状态更新前终止实例能否通过幂等消费恢复；
4. 任务租约过期、实例滚动退出和失败版本回滚是否可观测、可收敛；
5. 目标吞吐、P99、最老 Outbox 年龄和业务截止时间之间应采用什么 SLO。

## 6. 真实故障与观测证据

容量同轮通过：

- Prometheus 配置、4 个认证采集目标、12 条规则、Alertmanager 路由；
- Grafana Prometheus/Tempo 数据源与运维看板 provisioning；
- `PaymentSucceeded` 和 `RefundSucceeded` 两条 Payment HTTP→持久化 W3C 上下文→RocketMQ PRODUCER→Trade CONSUMER trace；
- Payment、Inventory、Trade、Fulfillment 四域“删除事实→OPEN/gauge→恢复事实→RESOLVED”对账；
- 完整正向交易与整单退货退款闭环。

独立故障矩阵通过：

- 停止 Trade：Payment→Trade 有界超时、有限重试、熔断快速拒绝、半开恢复，失败阶段零 Payment 脏写；
- 停止 Marketing：Trade→Marketing 熔断/舱壁、`PENDING_STOCK` 恢复、五订单五唯一锁、库存不提前预占；
- 停止 Redis：登录风控切换有界本地状态，Redis 恢复后回归；
- 所有中间件异常均返回失败、处理中或结果未知，没有伪造成功。

## 7. M3 最小风险切片

建议按以下顺序实施，每片都保留单实例回退开关：

1. 为 Trade Outbox 增加数据库 claim/lease、owner、claimed-at 与过期回收，先跑 1/2/3 实例等价性测试。
2. 容器化 Trade（必要时连同 Gateway），统一非 root 镜像、健康探针和优雅停机；其他服务保持单实例。
3. 在三个 Trade 实例上复跑相同 1000/100 基线，比较吞吐、P99、消息链收敛时间和 15 分钟前完成率。
4. 注入事务提交后/发送前、发送 ACK 后/标记前、消费事务后/ACK 前三个终止点。
5. 完成滚动升级、失败版本回滚和 Nacos/Gateway 持续可用证据，再把同一 claim/lease 模式扩展到恢复与对账任务。

前端可以在 M3 代表切片稳定后并行规划，但不应替代多实例正确性验证；三商户与 Go 仍等待 M9 进入条件。

