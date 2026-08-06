# Trade 订单恢复与 Outbox 调度隔离

## 1. 问题与完成口径

Trade 同时存在订单恢复、Outbox 发布和五个 RocketMQ 消费轮询。五个消费者的单次 `await-duration` 均可达到 5 秒；此前所有 `@Scheduled` 方法共享默认单线程调度器。2026-07-18 的 Marketing 停服实验记录到：首笔同步失败后，订单恢复调用间隔一度达到约 25 秒，配置的 `recovery-delay=5000` 并不能代表真实恢复时效，因此先隔离了订单恢复。

2026-07-20 的 Inventory 响应丢失真实冒烟进一步暴露：订单取消事实已提交，但
`OrderCanceled` 在 45 秒内仍未进入 RocketMQ。原因不是 Outbox 数据缺失，而是五个
最长 5 秒的 MQ 长轮询继续与 Outbox 共用默认单线程，Outbox 产生调度饥饿。当前实现
因此把 Outbox 隔离到独立调度器。

当前完成口径不是简单扩大公共线程池，而是：

- 保留默认调度器单线程，避免未经容量验证扩大 MQ 消费并发；
- 为订单恢复建立独立、上限为 1 的 `tradeOrderRecoveryScheduler`；
- 为 Outbox 扫描建立独立、上限为 1 的 `tradeOutboxScheduler`；
- 为三个线程池配置有界大小和最长 10 秒的关闭等待；
- 暴露执行结果、执行时长、运行状态、距上次完成时间以及原生 executor 饱和指标；
- 在自动测试和真实中间件环境证明默认调度线程被长轮询占用时，订单恢复与 Outbox 仍按自己的节奏推进。

这不宣称完成了多实例任务抢占、分片扫描或全服务调度治理。

## 2. 资源边界

| 调度器 | 池大小 | 当前工作负载 | 设计理由 |
| --- | --- | --- | --- |
| `taskScheduler` | 1 | Trade 的 MQ 长轮询 | 保持已有单实例消费并发语义 |
| `tradeOrderRecoveryScheduler` | 1 | 订单恢复与支付超时扫描 | 从阻塞轮询中隔离，单实例内避免同一任务并发重入 |
| `tradeOutboxScheduler` | 1 | Outbox 领取和发布入口 | 防止 MQ 长轮询延迟订单事实发布；实际跨聚合并行仍由有界发布 executor 控制 |

配置位于 `ecommerce.trade.scheduling`：

```yaml
default-pool-size: 1
order-recovery-pool-size: 1
outbox-pool-size: 1
shutdown-await: 10s
```

属性校验限制默认池最多 4、订单恢复池和 Outbox 池最多 2、关闭等待不超过 30 秒。当前值仍是个人电脑缩比基线，不根据 CPU 数自动无界扩容。

## 3. 指标与告警

Spring Boot 为三个 `ThreadPoolTaskScheduler` 提供原生指标：

```text
executor.active{name="taskScheduler|tradeOrderRecoveryScheduler|tradeOutboxScheduler"}
executor.queued{name="taskScheduler|tradeOrderRecoveryScheduler|tradeOutboxScheduler"}
executor.pool.size{name="taskScheduler|tradeOrderRecoveryScheduler|tradeOutboxScheduler"}
```

订单恢复另有低基数业务指标：

```text
ecommerce.task.scheduler.executions{service="trade-service",task="order_recovery",result="success|failure"}
ecommerce.task.scheduler.duration{service="trade-service",task="order_recovery"}
ecommerce.task.scheduler.running{service="trade-service",task="order_recovery"}
ecommerce.task.scheduler.completion.age{service="trade-service",task="order_recovery"}
```

Prometheus 在距上次完成超过 15 秒并持续 1 分钟时触发 `PlainJournalScheduledTaskDelayed` warning。Grafana 同时展示完成年龄、活跃线程、队列任务和池大小。业务编号、异常正文和线程名实例不进入标签。

## 4. 自动化证据

`TradeServiceApplicationTest` 使用真实 Spring 调度器：先在默认调度器提交一个阻塞任务并确认其唯一线程已被占用，再分别向订单恢复和 Outbox 调度器提交任务；两个隔离任务都在 500ms 断言窗口内执行，线程名前缀分别为 `trade-order-recovery-` 和 `trade-outbox-scheduling-`。测试还确认：

- 三个 executor 的原生指标均已注册；
- 订单恢复完成年龄 gauge 已注册；
- 成功和失败执行分别计数；
- 异常后 `running` 恢复为 0；
- 执行时长 timer 记录两次调用。

定向测试证明三个调度器相互隔离；当前全仓测试、PMD 和覆盖率数字统一见
[验证摘要](verification-summary.md)。

## 5. 真实环境证据

验证命令：

```powershell
cd backend
.\run-foundation-smoke.ps1 -EnableTradeMarketingResilienceFaultInjection -EnableObservability
```

真实 MySQL、Redis、Nacos、RocketMQ、MinIO、八个 Java 应用和观测栈同时运行。Marketing 停机后：

- 首次同步失败发生于 `15:23:01.314`；
- 专用 `trade-order-recovery-1` 在 `15:23:07.647` 再次调用，间隔约 6.33 秒，低于脚本的 12 秒硬上限；
- Actuator 能读取成功执行数、完成年龄和 `tradeOrderRecoveryScheduler` executor 指标；
- 首笔订单的初始失败与恢复失败，加上三笔故障订单形成最少五个逻辑失败并打开熔断，第五笔订单被本地拒绝；
- Marketing 恢复后五笔订单形成五个不同价格锁，取消后库存恢复为 `onHand=6/reserved=0/available=6`。

同轮完整正向交易、履约、整单退款、补偿及 Payment/Inventory 对账通过；Prometheus 四个实时目标、四个规则组共 12 条规则、Alertmanager 和 Grafana 全部通过。脚本结束后无 Java 端口残留。

2026-07-20 的 Inventory 响应丢失真实冒烟中，独立 Outbox 调度器消除了取消事件被
MQ 长轮询长期饥饿的问题；同轮正向、取消、营销、支付、履约、售后、退款和四域对账
全部通过。最终结论见
[M0-M8 三层工程验收](evidence/m0-m8-three-layer-acceptance-20260728.md)。

## 6. 当前边界

- 默认 MQ 长轮询仍按单线程串行执行；是否扩大并发必须先建立积压和消费吞吐基线。
- 独立 Outbox 调度器解决的是扫描入口时效，不替代 owner/lease、数据库前驱约束、幂等发布和 Broker ACK 后落状态。
- 单实例专用调度器本身不解决多实例重复扫描；当前多实例验证已经通过数据库任务抢占、
  租约/版本条件、幂等执行和实例退出证明该边界。
- 其他服务只有在真实出现任务互相阻塞证据后才复制隔离机制，不为了统一而批量增加线程。
