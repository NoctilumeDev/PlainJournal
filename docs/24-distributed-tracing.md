# Payment 到 Trade 分布式追踪代表链路

## 1. 当前边界

当前实现两条能跨事务和消息异步边界保存因果关系的代表链路：

```text
Payment 支付回调 HTTP server span
  -> Payment 本地事务写 PaymentSucceeded Outbox（保存 W3C traceContext）
  -> Outbox 定时发布 PRODUCER span
  -> RocketMQ message properties
  -> Trade CONSUMER span（业务处理和 ACK 同处该 span）

Payment 退款回调 HTTP server span
  -> Payment 本地事务写 RefundSucceeded Outbox（保存 W3C traceContext）
  -> Outbox 定时发布 PRODUCER span
  -> RocketMQ message properties
  -> Trade CONSUMER span（完成售后和 ACK 同处该 span）
```

这证明支付与退款两个关键 HTTP、数据库持久化异步边界和 RocketMQ 可以归入各自
同一条 trace，不代表所有服务的 HTTP、消息和补偿任务都已经完成端到端追踪。

## 2. 单一主线

- 业务侧 API：Micrometer Tracing。
- 唯一 bridge：`micrometer-tracing-bridge-otel`。
- 唯一导出协议：OTLP/HTTP。
- 唯一后端：Grafana Tempo 2.10.5。
- 唯一传播格式：W3C `traceparent` / `tracestate`；baggage 显式关闭。
- 不引入 OpenTelemetry Collector、Zipkin、SkyWalking 或第二套 SDK。

Payment 与 Trade 的 exporter 默认关闭，默认采样率为 10%。真实冒烟显式开启导出并将采样率设为 100%；测试环境保留真实 tracer/propagator，但关闭网络导出。

## 3. 跨 Outbox 的上下文保存

支付成功或退款成功事实与对应 Outbox 事件在同一 Payment 本地事务内提交。事件信封包含可选 `traceContext` 对象，保存当前 W3C carrier；旧事件没有该字段时仍可发布，只会从新的根 span 开始。解析异常同样不会伪造发布成功，事件仍按原 Outbox 状态机重试。

发布任务从持久化 carrier 恢复父上下文，创建 `rocketmq publish PaymentSucceeded` 或 `rocketmq publish RefundSucceeded` PRODUCER span；RocketMQ publisher 再把当前 W3C carrier 写入消息 properties。Trade 消费者从 properties 恢复父上下文，创建对应 CONSUMER span，并在该 span 内完成订单/售后状态推进和 ACK。业务处理或 ACK 失败时 span 标错且消息不确认，沿用原有重试语义。

trace 只用于定位，不参与支付、订单或消息状态裁决。Tempo 不可用或导出失败不能改变 MySQL/Outbox/RocketMQ 的业务结果。

## 4. 同步 HTTP 自动观测

Payment 与 Trade 的自定义 `RestClient` 仍保留连接/读取超时，但通过 Spring Boot 的 `RestClientBuilderConfigurer` 构建，使 Micrometer observation、负载均衡和现有 Resilience4j 包装可以同时工作。业务代码不手工拼接 HTTP trace header。

真实证据聚焦 Payment 回调到 Trade 消费；其余同步客户端只完成基础自动观测接入，
没有逐条宣告端到端完成。

## 5. 属性与安全边界

消息 span 使用 `messaging.system`、destination、operation、event type 和 message id 等定位属性。指标标签仍禁止订单号、用户 ID、消息 ID 和错误正文等高基数或敏感值；trace 的 24 小时本地保留不改变这一指标约束。不得把支付签名、JWT、地址、手机号、邮件或消息正文写入 span/baggage。

## 6. 自动与真实验证

自动测试覆盖：

- 带已知 W3C `traceparent` 的 Payment 回调把同 trace、不同 span 的 carrier 持久化到 Outbox；
- Outbox 首次发布失败后重试仍从持久化父上下文创建 PRODUCER span；
- Trade 从 RocketMQ properties 恢复同 trace 的 CONSUMER span，并在 span 内 ACK；
- Trade 业务异常时不 ACK，错误继续向原消费循环传播。
- 带已知 W3C 父上下文的退款回调把 `RefundSucceeded` carrier 持久化，Trade 恢复同 trace 并在完成售后的 CONSUMER span 内 ACK。

当前自动化测试数量和覆盖率统一见[验证摘要](verification-summary.md)。

真实验证命令：

```powershell
cd backend
.\run-foundation-smoke.ps1 -EnableDistributedTracing -EnableObservability
```

脚本先遵守本机网络预检，再按需启动 Tempo 和观测栈，执行基础交易正向/逆向闭环，
分别从 Payment 的支付与退款 Outbox 读取本次 `traceId`，通过 Tempo
`/api/traces/{traceId}` 验证：

- trace 同时包含 `payment-service` 和 `trade-service`；
- 包含 `rocketmq publish PaymentSucceeded`；
- 包含 `rocketmq consume PaymentSucceeded`。
- 退款 trace 包含 `rocketmq publish RefundSucceeded` 与 `rocketmq consume RefundSucceeded`。

验证通过后脚本只停止本次启动的 Java 进程和观测容器，保留原先运行的核心中间件与
仓库外数据卷。

## 7. 当前扩展规则

继续按风险和恢复价值补齐，而不是一次性给全部方法加 span。每条新增高风险链路都
必须包含自动传播测试、故障语义检查和真实 Tempo 查询证据；追踪失败不得改变业务
事务、Outbox 或消息 ACK 语义。
