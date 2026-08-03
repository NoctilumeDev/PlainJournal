# M3 Trade 容器多实例与优雅停机

> 验证日期：2026-07-20  
> 范围：M3 第二批，覆盖 Trade 容器化、Nacos 发现、容器内 Outbox 发布和优雅停止；不等价于 M3 整体完成

## 1. 本批目标

- 使用同一镜像按需扩展 1、2、3 个 Trade 实例；
- 容器使用非 root 身份、唯一实例 ID 和唯一 Outbox publisher ID；
- Docker liveness 与 Nacos 健康实例数必须准确收敛；
- 三个容器共享真实 MySQL Outbox，并通过真实 RocketMQ Proxy 发布；
- 停止一个实例时完成 Nacos 注销和 Spring Boot 优雅停机，不触发 `SIGKILL`；
- 中间件异常保持 Outbox `PENDING`，不伪造发布成功。

本批不覆盖 Gateway 入口流量分配、消费者组竞争、滚动升级、失败版本回滚和三个进程终止点。

## 2. 容器实现

Trade 镜像使用 `eclipse-temurin:17-jre-alpine`，运行时约束如下：

- 固定 UID/GID：`10001:10001`；
- `STOPSIGNAL SIGTERM`；
- Docker liveness healthcheck；
- 容器 IP 自动注入 `SERVICE_IP`；
- hostname 自动作为 `SERVICE_INSTANCE_ID` 与 `TRADE_OUTBOX_PUBLISHER_ID`；
- Spring Boot graceful shutdown；
- 单个 shutdown phase 最长 20 秒；
- Outbox 发布线程最多等待 10 秒收敛；
- Compose `stop_grace_period` 为 30 秒。

Compose 的 `m3-trade` profile 不常驻启动多实例。验证脚本依次执行 `--scale 1`、`--scale 2`、`--scale 3`，符合个人电脑“机制真实、规模缩比”的边界。

## 3. RocketMQ 双入口根因与修复

首次容器发布验证得到：

```text
PUBLISHED=0
PENDING=1000
PUBLISHING=0
attempts=10756
```

应用日志外层只显示 Producer 已进入 `FAILED`。RocketMQ Client 文件日志给出真实根因：

```text
UNAVAILABLE: io exception
Connection refused: /127.0.0.1:18082
```

Trade 容器最初能够连接 `plainjournal-rocketmq-broker:18082`，但原先内嵌于 Broker 的 local-mode Proxy 使用 `brokerIP1=127.0.0.1` 构造后续路由，容器收到 `127.0.0.1:18082` 后错误连接自身。TCP 可达与 DNS 正常不能证明 Proxy 返回的后续 endpoint 正确。

修复保留既有网络和端口约束：

- Broker 与 cluster-mode Proxy 拆为两个容器；
- Proxy 使用 `network_mode: service:rocketmq-broker` 与 Broker 共享网络命名空间；
- Broker 继续以 `127.0.0.1:10911` 提供内部 Remoting；
- 宿主机入口仍为 `127.0.0.1:18082`；
- Compose 内入口仍为 `plainjournal-rocketmq-broker:18082`；
- gRPC 内外端口始终为 18082，Remoting Proxy 仍为 `18081 -> 8080`；
- Proxy 启动前等待 `EcommerceCluster` 已向 NameServer 注册，消除冷启动重启竞态。

Trade Outbox 错误摘要同时改为保留最深层 cause，后续连接拒绝、超时和协议错误不再被 `CompletionException` 外层覆盖。

## 4. 真实容器验证

正式命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\verify-trade-container-multi-instance.ps1 `
  -SkipNetworkPreflight `
  -EventCount 1000 `
  -TimeoutSeconds 180
```

小样本 100 条已先验证链路恢复：100 条全部发布，失败、重试、状态冲突和顺序违规均为 0；因为批次较小，只有两个实例实际抢到任务。脚本提供 `-AllowPartialPublisherParticipation` 用于这种连通性小样本；正式 1000 条默认仍要求三个实例全部参与。

正式 1000 条结果：

| 指标 | 结果 |
| --- | ---: |
| 总事件 | 1000 |
| 收敛时间 | 16212.205 ms |
| 吞吐 | 61.682 events/s |
| 实例 1 成功 | 350 |
| 实例 2 成功 | 311 |
| 实例 3 成功 | 339 |
| Outbox attempts | 0 |
| 发布失败 | 0 |
| 状态冲突 | 0 |
| 同聚合顺序违规 | 0 |

1、2、3 实例阶段均验证：

- 运行 UID 为 10001；
- 容器 IP、实例 ID、publisher ID 唯一；
- Docker health 为 `healthy`；
- Nacos 健康实例数分别收敛为 1、2、3。

镜像证据：

| 项目 | 结果 |
| --- | --- |
| 镜像 | `plainjournal/trade-service:local` |
| 大小 | 267209651 bytes |
| Runtime user | `10001:10001` |
| Stop signal | `SIGTERM` |

机器证据保存在忽略文件：

`backend/.run/trade-container-multi-instance.json`

## 5. 优雅停止

正式轮停止第三个实例：

- Docker stop：677.428 ms；
- ExitCode：143；
- 未出现强制终止 137；
- Nacos 健康实例数：3 -> 2；
- 日志包含 `De-registration finished.`；
- 日志包含 `Graceful shutdown complete`。

Nacos Client 3.0.3 在完成注销和 Tomcat graceful shutdown 后，仍可能记录 `NotifyCenter` 关闭期 `InterruptedException` 或 `sharePublisher` 空指针。验证脚本把它记录为已知上游关闭问题，但不会把 ERROR 隐藏或误判为业务成功。当前不为消除该日志盲目升级整套 BOM。

## 6. 宿主机兼容性回归

Broker/Proxy 拆分后重新执行：

```powershell
.\verify-trade-outbox-multi-instance.ps1 `
  -SkipNetworkPreflight `
  -EventCount 1000 `
  -TimeoutSeconds 180
```

结果：

| Trade 实例 | 事件 | 耗时 | 吞吐 |
| ---: | ---: | ---: | ---: |
| 1 | 1000 | 4706.075 ms | 212.491 events/s |
| 2 | 1000 | 4573.742 ms | 218.639 events/s |
| 3 | 1000 | 5106.408 ms | 195.832 events/s |

三组均为 1000 条发布、0 重试、0 状态冲突、0 顺序违规；三实例过期 owner 租约在 2063.321 ms 内回收。由此证明新 Proxy 拓扑同时支持宿主机与 Compose 网络入口，没有以修复容器链路为代价破坏原有本地验证。

## 7. 自动化与静态门禁

本批收口后执行：

```powershell
mvn clean verify
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
```

结果：

- 11 个 Reactor 模块全部成功；
- 40 份 Surefire 报告共 134 个测试；
- 0 失败、0 错误、0 跳过；
- Trade 46 个测试；
- PMD 3.28.0 / PMD 7.17.0 全 Reactor 0 违规；
- PowerShell 脚本解析 0 错误；
- Compose 全 profile 配置校验通过；
- `git diff --check` 通过。

## 8. 结论与下一批

M3 第二批证明了 Trade 可重复容器化、1/2/3 实例发现、真实容器内消息发布与单实例优雅退出。当时尚未证明的消费者竞争、三个进程终止点和完整业务入口滚动发布，现已由第三批完成，详见 [M3 消费者竞争、进程终止与发布治理](29-m3-consumer-fault-and-release-governance.md)。

第三批已完成：

1. 三个可控进程终止点；
2. `PaymentSucceeded` 消费者 1/2/3 实例竞争和重复投递；
3. Gateway/Nacos promotion、摘流、滚动替换和失败候选回退。

M3 仍需真实双版本兼容与完整业务 1000 请求、100 并发容量复测。

## 9. 后续收口

真实 stable/candidate 双版本兼容、最终滚动发布与 1000 请求、100 并发容量复测已于 2026-07-20 完成，M3 已关闭。最终结果见 [M3 双版本兼容、滚动发布与容量复测](31-m3-dual-version-and-capacity.md)。
