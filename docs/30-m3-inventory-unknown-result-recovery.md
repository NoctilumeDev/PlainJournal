# M3 Inventory 预占结果未知恢复

> 验证日期：2026-07-20  
> 范围：M3 第四批，覆盖 Inventory 预占事务已提交但 Trade 未收到 HTTP 响应时的权威查询恢复，以及真实冒烟暴露的 Nacos、Marketing 和 Trade Outbox 配套缺陷；不等价于 M3 整体毕业

## 1. 本批目标

验证以下真实分布式故障：

```text
Trade 发送库存预占 POST
  -> Inventory 完成 MySQL 本地事务并返回 HTTP 200
  -> Trade 读取响应前连接被断开
  -> Trade 只知道“结果未知”
```

完成口径：

- 不把网络异常伪造成库存预占失败或成功；
- 不使用新的预占号重复扣减库存；
- 使用稳定 `reservationNo` 查询 Inventory 所有者域事实；
- 只有查询结果与原命令严格一致时才推进订单；
- 查询仍失败时保留 `PENDING_STOCK`，通过既有恢复任务退避重试；
- 自动测试和真实 MySQL、Nacos、RocketMQ 链路都能复现并证明收敛。

## 2. Trade 恢复语义

Trade 的库存端口返回完整 `ReservationSnapshot`：

- `reservationNo`
- `orderNo`
- `status`
- `warehouseId`
- `expiresAt`
- SKU 与数量集合

预占流程为：

```text
reserve(command)
  -> 成功：校验完整快照，按状态推进
  -> 异常：getReservation(command.reservationNo)
       -> 查询成功且完整匹配：按权威状态推进
       -> 查询失败或事实不匹配：保留 PENDING_STOCK 并调度恢复
```

严格校验覆盖预占号、订单号、仓库、过期时间和排序后的 SKU/数量集合。任何字段不一致都返回 `IDEMPOTENCY_CONFLICT`，不能把别的订单或不同命令的库存事实绑定到当前订单。

权威状态处理：

| Inventory 状态 | Trade 处理 |
| --- | --- |
| `RESERVED` | 迁移到 `PENDING_PAYMENT` |
| `REJECTED` / `RELEASED` / `EXPIRED` | 关闭为缺货 |
| 其他状态 | 保留 `PENDING_STOCK` 并重试 |

从未知结果恢复时，订单历史记录：

```text
command = RESOLVE_STOCK_RESULT
reason  = RESERVE_RESPONSE_UNKNOWN
```

指标使用固定低基数标签：

```text
ecommerce.trade.inventory.reservation.unknown.result.resolutions{
  service="trade-service",
  dependency="inventory-service",
  operation="reserve",
  outcome="recovered|unresolved"
}
```

## 3. 真实 TCP 响应丢失注入

`backend/tools/inventory-response-drop-proxy.ps1` 只对一次已武装的库存预占 POST 注入故障：

1. 完整转发请求到真实 Inventory；
2. 完整读取 Inventory 的成功响应；
3. 记录状态码、响应字节数、SHA-256 和 `reservationNo`；
4. 主动断开 Trade 连接，不把响应正文返回给 Trade；
5. 后续 GET 查询正常透传。

这比在客户端直接抛 Mock 异常多证明了一个关键事实：Inventory 已经成功提交并生成可核对的 HTTP 200 响应，丢失发生在服务边界之后。

冒烟开关：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\run-foundation-smoke.ps1 `
  -SkipNetworkPreflight `
  -EnableInventoryReservationResponseLossFaultInjection
```

故障代理以本轮专用 Nacos 服务 `inventory-response-loss-proxy` 注册。为避免临时实例心跳和脚本进程生命周期耦合，服务与实例都使用 `ephemeral=false`，并通过 Nacos v3 Admin instance API 注册和反注册。清理阶段只删除本轮创建的服务；验证结束后查询返回 HTTP 404，没有残留。

## 4. 真实验证证据

最终结果：

```text
Foundation smoke test: PASS
Trade:     PENDING_PAYMENT|0|1|1
Inventory: RESERVED|1|1
Metric:    recovered = 1
```

`Trade` 四段事实依次表示：

1. 订单状态为 `PENDING_PAYMENT`；
2. 恢复尝试数已清零；
3. 一条 `RESOLVE_STOCK_RESULT / RESERVE_RESPONSE_UNKNOWN` 历史；
4. 一条 `OrderAwaitingPayment` Outbox。

`Inventory` 三段事实依次表示：

1. 预占状态为 `RESERVED`；
2. 一条 `RESERVE` 库存流水；
3. 一条库存 Outbox。

代理记录：

```text
upstreamStatus: 200
upstreamResponseBytes: 321
upstreamResponseSha256:
4a95e679f3675577af7291b3d94b6b9c7fd258f1e0eaa7d4c140a3fc28a9a5ef
```

机器证据保存在忽略文件：

`backend/.run/inventory-reservation-response-loss.json`

同轮完整正向、取消、营销、支付、履约、售后、退款和 Trade、Payment、Inventory、Fulfillment 四域对账全部通过；取消后库存完全恢复。

## 5. 真实冒烟带出的配套修复

### 5.1 Marketing 无锁订单兼容

历史订单可能没有营销价格锁。旧实现先插入 `consumed_event`，随后内层 `RESOURCE_NOT_FOUND` 抛出并被外层捕获，但事务已经被标记为 rollback-only，导致消费标记回滚、消息永久重试并阻塞新事件。

`OrderPaid`、`OrderCanceled` 和 `OrderClosed` 现在调用 `redeemIfPresent` / `releaseIfPresent`。无价格锁是正常兼容空操作；有价格锁时，消费标记和权益核销/释放仍在同一本地事务。

### 5.2 Trade Outbox 调度饥饿

真实冒烟发现订单取消已经提交，但 45 秒内没有 `OrderCanceled` 进入 RocketMQ。五个最长 5 秒的 MQ 长轮询与 Outbox 共用单线程 `taskScheduler`，导致 Outbox 调度饥饿。

Trade 新增独立单线程 `tradeOutboxScheduler`，与 `taskScheduler`、`tradeOrderRecoveryScheduler` 形成三个有界调度器。自动测试在默认调度线程被阻塞时，证明 Outbox 仍能在 500ms 窗口内执行。该隔离只解决扫描入口时效；多实例正确性仍由 owner/lease、数据库顺序约束和幂等发布保证。

## 6. 自动化覆盖

- `TradeFlowIntegrationTest` 覆盖：
  - POST 异常后查询到已提交预占并恢复；
  - 查询仍失败时保留 `PENDING_STOCK` 和退避信息；
  - 查询事实与原命令不一致时拒绝推进；
  - recovered/unresolved 指标；
  - 历史和 Outbox 精确一次。
- `HttpInventoryClientResponseLossTest` 使用真实本地 HTTP 连接复现服务端提交后直接断开，再通过 GET 取回完整预占。
- `MarketingFlowIntegrationTest` 覆盖无营销锁的支付、取消事件及重复投递。
- `TradeServiceApplicationTest` 覆盖默认调度器阻塞时 Outbox 调度器独立执行。

本批定向执行结果：

- Marketing：5 个测试通过；
- Trade：55 个测试通过；
- 公共模块：7 个测试通过。

最终收口门禁：

- 11 个 Reactor 模块全部成功；
- 43 份 Surefire 报告共 144 个测试；
- 0 失败、0 错误、0 跳过；
- Trade 55 个测试，Marketing 6 个测试，公共模块 7 个测试；
- PMD 3.28.0 / PMD 7.17.0 全 Reactor 0 违规；
- PowerShell 脚本解析通过；
- Compose `core`、`core + observability`、`core + m3-trade`、`core + m3-gateway` 配置通过；
- `git diff --check` 通过。

## 7. 结论与剩余边界

M3 第四批已经证明：

- “HTTP 异常”与“业务失败”被明确区分；
- Inventory MySQL 事实已经提交时，Trade 能按稳定业务号恢复；
- 恢复不会重复预占、不会伪造成功、不会绑定不匹配事实；
- 真实冒烟发现的 Nacos 固定实例、Marketing 毒消息和 Trade Outbox 调度问题已形成回归保护。

本批没有证明：

- stable/candidate 使用真实不同业务二进制时的 HTTP、数据库迁移和事件版本兼容；
- 完整正逆向业务链的 1000 请求、100 并发容量复测；
- 此前 1018.326 秒消息链长尾在完整业务容量场景中的改善幅度。

截至第四批，M3 尚未整体关闭；当时的下一最小风险切片是双版本兼容验证和最终候选版本完整容量复测。三商户与 Go 异构服务仍保留在后续平台演进阶段。

## 8. 后续收口

真实 stable/candidate 双版本兼容与最终 1000 请求、100 并发容量复测已于 2026-07-20 完成。复测进一步修复了 Inventory Outbox 在大量拒绝事件前的饥饿问题，支付链从 1018.326 秒收敛到 71.887 秒，逆向链在 10.922 秒内完成，M3 已关闭。最终结果见 [M3 双版本兼容、滚动发布与容量复测](31-m3-dual-version-and-capacity.md)。
