# 同步调用韧性：Payment→Trade 与 Trade→Marketing

## 1. 范围与完成口径

M2 当前治理了两个语义不同的代表边界：

| 调用边界 | 类型 | 失败时的领域事实 | 允许有限重试的依据 |
| --- | --- | --- | --- |
| Payment→Trade 支付上下文 | 只读 `GET` | Payment 不写支付单，返回依赖不可用 | 查询天然幂等 |
| Trade→Marketing 权益锁定 | 有副作用 `POST` | Trade 保持 `PENDING_STOCK`，记录恢复信息，取得锁价事实前不预占库存 | 稳定 `orderNo`、Marketing 唯一约束、`requestHash` 冲突校验和重复请求返回原锁 |

第二个边界不是把“命令可重试”泛化为默认规则。它先证明 Marketing 已经具备业务幂等和结果唯一性，再开放最多两次总尝试；如果响应丢失，重试同一 `orderNo` 只会取得原锁。其他同步查询和命令仍须按幂等性、调用频率、失败代价和下游容量分别设计，不能复制同一组参数，也不能建设隐藏业务语义的万能客户端。

## 2. 失败语义

| 失败 | 重试 | 计入熔断 | 领域结果 |
| --- | --- | --- | --- |
| 连接、读取或负载均衡传输失败 | 是，最多 2 次总尝试 | 是 | Payment 返回 `503` 且不落库；Trade 保持 `PENDING_STOCK` |
| 下游 `5xx` | 是，最多 2 次总尝试 | 是 | 重试耗尽后按依赖不可用处理 |
| 已识别的 Marketing 业务 `4xx` | 否 | 否 | 按业务拒绝关闭订单，不伪造成系统故障 |
| 其他下游 `4xx` | 否 | 否 | 保持调用方既有依赖不可用契约 |
| 非法 `2xx` 响应 | 否 | 是 | 视为依赖协议失败，不接受不完整事实 |
| 熔断拒绝 | 不发起远程 I/O | 已处于保护状态 | 快速失败并增加拒绝计数 |
| 舱壁拒绝 | 不发起远程 I/O | 否 | 快速失败并增加拒绝计数 |

两个边界都不返回空对象、默认金额或伪造成功。Payment 无法取得 Trade 权威事实时不写支付单；Trade 无法确认 Marketing 锁价事实时不进入库存预占，而是保存可查询、可恢复的明确中间态。

## 3. 预算与组合顺序

两个边界当前使用相同的本地缩比初值，但分别持有独立配置和独立实例，后续可以按容量证据单独调整：

| 参数 | 值 |
| --- | --- |
| 连接超时 | 500 ms |
| 读取超时 | 1500 ms |
| 总预算上限 | 5000 ms |
| 最大总尝试 | 2 |
| 尝试间等待 | 100 ms |
| 最大并发调用 | 8 |
| 舱壁等待 | 0 ms |
| 熔断窗口 / 最少样本 | 10 / 5 |
| 失败率阈值 | 50% |
| OPEN 等待 | 10 s |
| HALF_OPEN 探测 | Payment 2；Trade 2 |

应用启动时校验：

```text
(连接超时 + 读取超时) × 最大尝试数 + 重试等待总时长 <= 总预算
```

配置一旦可能突破总预算，对应应用拒绝启动。装饰顺序从外到内为“并发舱壁 → 熔断 → 有限重试 → HTTP”；一次逻辑调用在重试等待期间仍占用舱壁许可，熔断按最终逻辑结果计一次，避免单个请求的两次尝试放大失败样本。

## 4. 指标与告警

实例名分别为 `paymentTradePaymentContext` 和 `tradeMarketingPricingLock`。Spring Boot Actuator 暴露 Resilience4j 的 circuit breaker、retry 和 bulkhead 指标；额外的低基数计数器为：

```text
ecommerce.http.client.resilience.rejections
  service=payment-service dependency=trade-service operation=payment_context guard=circuit|bulkhead
  service=trade-service   dependency=marketing-service operation=pricing_lock  guard=circuit|bulkhead
```

Prometheus 的 `plainjournal-synchronous-resilience` 规则组对两个实例统一判断：任一受治理熔断器持续 OPEN 30 秒为 critical，5 分钟内出现任一同步拒绝为 warning。Grafana 展示两个实例的 OPEN/HALF_OPEN 状态和按 `service/dependency/guard` 区分的拒绝速率。标签不包含订单号、用户 ID 或异常正文。

## 5. 自动化证据

`HttpTradeClientResilienceTest` 与 `HttpMarketingClientResilienceTest` 使用 JDK 本地 HTTP Server 建立真实 TCP 连接，覆盖：

- 首次 `503` 或响应丢失、第二次成功；
- 业务 `4xx` 不重试且不污染熔断样本；
- 非法成功响应不重试，但作为依赖失败计入熔断；
- 读取超时在有限次数内结束；
- 熔断 OPEN、HALF_OPEN 探测和成功关闭；
- 并发舱壁立即拒绝且不发起第二个远程请求；
- 最坏重试时长超过总预算时启动配置被拒绝。

Marketing TCP 用例还验证第一次连接在返回前断开、第二次返回同一锁号，证明客户端使用同一业务命令重试。Marketing 自身的集成测试和数据库唯一约束负责证明真实领域幂等，而不是由 TCP 桩伪造数据库语义。Payment 集成测试断言 Trade 不可用时 `payment_order` 为零；两个应用上下文测试确认原生指标和自定义拒绝计数器实际注册。

当前自动化测试数量和覆盖率统一见[验证摘要](verification-summary.md)；本节只维护两个
同步边界的故障语义和完成条件。

## 6. 真实中间件与故障证据

Payment→Trade 验证命令：

```powershell
cd backend
.\run-foundation-smoke.ps1 -EnableSynchronousResilienceFaultInjection
```

脚本先建立可支付订单，再停止 Trade。5 个逻辑失败使 Payment 熔断开启，第 6 次调用被快速拒绝；Actuator 显示 OPEN，拒绝计数增加，Payment MySQL 保持零新增。Trade 重启并等待 OPEN 窗口后，两个成功探测使 `HALF_OPEN -> CLOSED`，相同支付幂等键仍只产生一笔支付。

Trade→Marketing 验证命令：

```powershell
cd backend
.\run-foundation-smoke.ps1 -EnableTradeMarketingResilienceFaultInjection
```

脚本在真实 MySQL、Redis、Nacos、RocketMQ、MinIO 和所需核心服务上停止 Marketing。
首笔订单的同步失败和一次独立调度恢复失败，加上三笔故障订单形成最少 5 个逻辑失败
并打开熔断，第 5 笔订单被本地快速拒绝；5 笔订单均保持 `PENDING_STOCK`，Trade 保存
恢复事实，Marketing 没有锁，Inventory 没有预占。Marketing 恢复并越过 OPEN 窗口
后，订单恢复任务使 5 笔订单全部进入 `PENDING_PAYMENT`，Marketing MySQL 严格存在
5 个不同锁号；取消全部订单后库存恢复为 `onHand=6/reserved=0/available=6`。同轮
完整正向交易、履约、整单退款、补偿和 Payment/Inventory 对账全部通过，脚本最后
清理临时数据和 Java 进程。

同日观测栈验证确认 Prometheus、Alertmanager 和 Grafana 的四个实时采集目标健康，四个规则组共 12 条规则有效，包含两个同步韧性实例和 Trade 调度指标的新看板已加载。

## 7. 当前扩展边界

- 首轮真实故障发现 Trade 订单恢复与多个 RocketMQ 长轮询共享默认单线程，5 秒扫描可能推迟到约 25 秒。后续批次已将订单恢复隔离到独立、上限为 1 的调度器，并增加执行、完成年龄和 executor 饱和指标；相同停服场景实测 6.33 秒发生第二次调用，低于 12 秒硬上限。详见 [Trade 订单恢复调度隔离](23-trade-scheduling-isolation.md)。
- 继续按风险治理其余同步边界，只对只读或具有稳定幂等键、唯一约束和结果查询的命令开放有限重试。
- 分布式追踪已使用 Micrometer Tracing、单一 OpenTelemetry bridge 和 Tempo，代表
  链路见[分布式追踪](24-distributed-tracing.md)。
- 三实例抢占、消费者竞争、实例退出和滚动恢复已经完成；后续新增同步边界仍须按同一
  风险准入规则独立验证。
