# M3 消费者竞争、进程终止与发布治理

> 验证日期：2026-07-20  
> 范围：M3 第三批，覆盖 Trade 消费者多实例、真实进程终止、Gateway/Nacos 滚动替换和失败候选回退；不等价于 M3 整体毕业

## 1. 本批目标

- 验证 `PaymentSucceeded` 真实消费者组在 1、2、3 个 Trade 实例下竞争消费；
- 重复投递 1000 条事件，业务副作用仍保持精确一次；
- 在三个真实边界使用 `Runtime.halt(91)` 终止 JVM，并验证最终恢复：
  - `OUTBOX_BEFORE_PUBLISH`
  - `OUTBOX_AFTER_BROKER_ACK`
  - `CONSUMER_AFTER_COMMIT`
- 使用 Gateway 与 Nacos 完成稳定双实例到候选双实例的逐实例替换；
- 失败候选不能污染服务发现或中断已知健康版本；
- 所有验证使用真实 MySQL、Nacos、RocketMQ 和 Docker 进程，不使用 Mock 代替分布式证据。

## 2. 消费者与故障注入实现

Trade 新增默认关闭、必须绑定目标 `eventId` 的进程终止注入器。只有同时满足开关、故障点和目标事件三个条件时才执行：

```java
Runtime.getRuntime().halt(91);
```

该方式不运行 Spring shutdown hook，能够保留“事务已经提交但消息尚未 ACK”等真实进程边界。常规配置和测试默认关闭，不能通过宽泛开关随机终止业务进程。

消费者新增两个指标：

- `ecommerce.messaging.consumer.acknowledgements`
- `ecommerce.messaging.consumer.redelivery.acknowledgements`

第一个统计完成 ACK 的消息，第二个只统计已存在幂等消费记录后再次确认的重投消息。

## 3. 隔离 RocketMQ 的必要性

首次使用共享 Broker 验证 `CONSUMER_AFTER_COMMIT` 时，业务数据库已经处于正确状态：

```text
PUBLISHED|PAID|1|1|1|0
```

但消息在 300 秒内没有重投。Broker 日志与存储进度证明共享 RocketMQ 5.3.2 的 Timer Store 已异常：

```text
Fail to read msg from commitLog offsetPy:41291351 sizePy:574
```

Timer enqueue 进度长期停滞，消费位点已推进但 retry topic 没有目标事件。这不是 Trade 事务、幂等或 ACK 代码错误。项目没有删除共享 Broker 数据或重置其存储，而是为 M3 故障验证启动一次性的隔离 NameServer、Broker 和 Proxy：

- 使用相同的 `apache/rocketmq:5.3.2`；
- 使用真实 Docker 网络和 Proxy gRPC 端口 18082；
- 使用本次运行专属 Topic、Consumer Group 和 Docker volume；
- 新命名卷先把 `/home/rocketmq/store` 归属设置为镜像运行用户 `3000:3000`；
- 验证结束只删除本次精确命名的三个容器和临时卷。

隔离 Broker 启动检查确认集群注册、18082 监听和 Timer Store 日志正常。共享 Broker 及其业务数据未被修改。

## 4. 1000 条消费者正式验证

正式命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\verify-trade-consumer-multi-instance.ps1 `
  -SkipNetworkPreflight `
  -SkipBuild `
  -EventCount 1000 `
  -TimeoutSeconds 300
```

结果：

| Trade 实例 | 输入事件 | ACK | 活跃消费者 | 耗时 | 吞吐 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1000 | 1000 | 1 | 231805.217 ms | 4.314 events/s |
| 2 | 1000 | 1000 | 2 | 178601.387 ms | 5.599 events/s |
| 3 | 1000 | 1000 | 3 | 95614.032 ms | 10.459 events/s |

三实例重复投递同一批 1000 条输入事件后：

| 业务事实 | 数量 |
| --- | ---: |
| `PAYMENT_SUCCEEDED` 状态历史 | 1000 |
| `OrderPaid` Outbox | 1000 |
| `consumed_event` | 1000 |
| 订单 `PAID` | 1000 |

重复 ACK 不会再次修改订单、追加状态历史或创建第二条 `OrderPaid` 事件。

## 5. 三个真实进程终止点

| 故障点 | 终止时数据库状态 | 恢复后状态 | 关键证据 |
| --- | --- | --- | --- |
| `OUTBOX_BEFORE_PUBLISH` | `PUBLISHING\|PENDING_PAYMENT\|0\|0\|0\|0` | `PUBLISHED\|PAID\|1\|1\|1\|1` | 过期 owner/lease 回收后重新发布 |
| `OUTBOX_AFTER_BROKER_ACK` | `PUBLISHING\|PENDING_PAYMENT\|0\|0\|0\|0` | `PUBLISHED\|PAID\|1\|1\|1\|1` | Broker 已收消息，本地重发产生 2 次 ACK，业务副作用一次 |

> 上表是 2026-07-20 旧 stable 工作流的历史故障向量。当前 candidate 在
> `PaymentSucceeded` 后先进入 `PAYMENT_CONFIRMING`，必须等待 Inventory 权威确认后
> 才到 `PAID`；当前发布边界见
> [双版本语义复审](31-m3-dual-version-and-capacity.md#5-双向线级兼容与工作流语义边界)。
| `CONSUMER_AFTER_COMMIT` | `PUBLISHED\|PAID\|1\|1\|1\|0` | `PUBLISHED\|PAID\|1\|1\|1\|1` | 隔离 Timer Store 完成重投，redelivery ACK 为 1 |

机器证据保存在忽略文件：

`backend/.run/trade-consumer-multi-instance.json`

## 6. Gateway 与 Nacos 发布治理

Gateway 新增 JRE 17 非 root 镜像：

- UID/GID：`10001:10001`
- Stop signal：`SIGTERM`
- Docker liveness probe
- Compose profile：`m3-gateway`

Trade 状态接口在原有成功响应上增加只读字段：

- `instanceId`
- `releaseId`

Nacos 实例元数据同步保存这两个字段。它们只用于发布验证和运维定位，不参与订单、库存或支付裁决。

最初的“注册后直接停止”基线暴露了两个真实问题：

1. Nacos 列表已经收敛时，Gateway 仍可能短暂缓存旧实例，直接停止会产生连接拒绝和 HTTP 500；
2. 数据库凭据错误的候选进程可能先注册 Nacos，再因 Flyway/Hikari 启动失败退出，继续污染 Gateway 实例缓存。

最终发布流程为：

```text
容器启动
  -> Docker health 通过
  -> Nacos 显式 enabled=true（promotion gate）
  -> Gateway 观察到新旧实例
  -> 待停实例 enabled=false（drain）
  -> 连续 30 次 Gateway 请求不再命中待停实例
  -> SIGTERM 优雅停止
  -> 替换下一个实例
```

失败候选设置：

```text
spring.cloud.nacos.discovery.register-enabled=false
```

只有启动和健康门禁通过的版本才允许进入服务发现。

Nacos 的人工 `enabled=false` 状态会按 `IP:port` 保留，Docker 默认网桥复用 IP 时可能把旧摘流状态带给新容器。验证脚本因此创建本轮专属发布网络，为稳定、候选和失败实例分配唯一静态 IP，并把 Gateway 同时接入该网络。MySQL、Redis 和 Nacos 仍走原有真实中间件网络；实验网络结束后精确删除，不修改宿主机默认路由或共享 Docker 网络。

正式命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\verify-gateway-rolling-upgrade.ps1 `
  -SkipNetworkPreflight `
  -TimeoutSeconds 300 `
  -ProbeIntervalMilliseconds 100
```

正式结果：

| 指标 | 结果 |
| --- | ---: |
| Gateway 连续请求 | 1439 |
| HTTP/业务失败 | 0 |
| 非预期 release | 0 |
| 初始实例 | `trade-stable-1/2` |
| 最终实例 | `trade-candidate-1/2` |
| 失败候选退出码 | 1 |
| 失败候选进入 Nacos | 否 |
| 失败回退期间 Gateway 中断 | 否 |

机器证据保存在忽略文件：

`backend/.run/gateway-rolling-upgrade.json`

## 7. 自动化与静态门禁

本批最终执行：

```powershell
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
mvn clean verify
```

结果：

- 11 个 Reactor 模块全部成功；
- 42 份 Surefire 报告共 139 个测试；
- 0 失败、0 错误、0 跳过；
- Trade 51 个测试；
- PMD 3.28.0 / PMD 7.17.0 全 Reactor 0 违规；
- PowerShell 脚本解析通过；
- Compose `core/observability/m3-trade/m3-gateway` 配置通过；
- `git diff --check` 通过。

## 8. 结论与剩余边界

M3 第三批已经证明：

- 一个真实消费者组能够在 1、2、3 个实例间竞争；
- 重复消息和三个进程终止边界不会制造重复订单副作用；
- Gateway/Nacos 发布必须显式包含 promotion、drain 和失败候选禁止注册；
- 在个人电脑缩比环境中，两实例滚动替换和失败候选回退可以保持入口请求连续成功。

本批没有证明：

- stable/candidate 使用不同业务二进制时的 HTTP、数据库迁移和事件版本兼容；
- 库存预占成功但 HTTP 响应丢失的结果未知恢复；
- 完整正逆向业务链的 1000 请求、100 并发容量复测，以及此前 1018.326 秒消息长尾是否消除。

截至第三批，M3 尚未整体关闭；当时的下一最小风险切片是完成真实双版本兼容与完整业务容量复测。多商户和 Go 异构服务仍保留在 M9。

## 9. 后续收口

真实 stable/candidate 双版本兼容、Gateway/Nacos 最终滚动、坏候选回滚和完整 1000 请求、100 并发容量复测已于 2026-07-20 完成，M3 已关闭。最终结果见 [M3 双版本兼容、滚动发布与容量复测](31-m3-dual-version-and-capacity.md)。
