# 指标采集、看板与告警

## 1. 本批目标

本批把 M2 已存在于应用内的 Outbox、消费失败和业务处理中指标接入真实指标后端，并加入首条跨服务追踪代表链路，形成“安全采集、统一查询、状态看板、分级告警、按 trace 定位”的最小闭环。

它不建设一个常驻的大型观测平台，也不把观测组件变成交易服务的启动依赖。Prometheus、Alertmanager、Grafana 和 Tempo 位于独立 `observability` Compose profile，默认不启动；四个组件同时运行的内存上限约为 1.5 GiB。关闭它们不会改变订单、库存、支付、退款或消息状态。

## 2. 拓扑与责任

```text
Trade / Inventory / Payment / Fulfillment / Marketing
  /actuator/prometheus
  独立 X-Metrics-Token 身份
          |
          v
     Prometheus  ----告警规则----> Alertmanager 本地告警中心
          |
          v
       Grafana
  PlainJournal Operations

Payment / Trade
  OTLP/HTTP traces
          |
          v
        Tempo
          |
          v
       Grafana
```

- Prometheus 每 10 秒抓取五个交易核心服务，保存 7 天本地时序数据。
- Alertmanager 负责分组、去重、静默和本地状态展示。首批不配置邮件、短信或聊天软件接收方，避免开发环境误发外部通知。
- Tempo 接收 Payment 与 Trade 显式开启的 OTLP/HTTP trace，使用本地 WAL/blocks 并保留 24 小时。
- Grafana 自动配置 Prometheus、Tempo 两个数据源和 `PlainJournal Operations` 看板。
- 应用仍由各自 MySQL schema 保存最终业务事实；指标后端不是审计账本，也不参与补偿决策。

## 3. 采集身份与安全边界

`/actuator/prometheus` 不匿名开放，也不复用会过期的管理员 JWT。五个服务共享以下规则：

| 请求身份 | 结果 |
| --- | --- |
| 无凭据 | `401` |
| 普通顾客 JWT | `403` |
| 错误 `X-Metrics-Token` | `401` |
| 正确独立采集令牌 | `200` |
| `ADMIN` JWT | `200` |

`bootstrap-resources.ps1` 首次运行时生成至少 32 字符的 `METRICS_SCRAPE_TOKEN`，只写入被忽略的 `deploy/docker/.env`，并同步到被忽略的 `.runtime-secrets/metrics-scrape-token`。Prometheus 通过 Compose secret 和官方 `http_headers.files` 能力读取令牌，源码、Prometheus 配置页和命令输出均不包含令牌值。

应用未配置采集令牌时，专用 `METRICS` 身份自动禁用，管理员 JWT 仍可诊断；若配置了短于 32 字符的令牌，应用拒绝启动。令牌比较使用恒定时间字节比较。轮换令牌时应修改 `.env`、重跑引导脚本并重启五个应用和 Prometheus，避免新旧凭据窗口不一致。

该共享 secret 适用于本机隔离开发网络。真实跨主机部署还需要 TLS/mTLS、网络策略和外部 secret manager，不能把单个 Header 当作完整生产信任模型。

## 4. 指标与告警

看板覆盖：

- 五个采集目标可用性；
- Outbox 未发布数量、最老未发布年龄和发布结果速率；
- 消费失败的 `retrying` / `needs_attention` 数量和最老年龄；
- Trade 订单/售后与 Payment 退款的处理中数量和最老状态年龄。
- Payment 与 Inventory 所有者域对账的未关闭问题数量，按 `service` 标签区分。
- Payment→Trade 与 Trade→Marketing 两个受治理熔断器的 `OPEN/HALF_OPEN` 状态，以及按服务、依赖和熔断/舱壁区分的同步调用拒绝速率。
- Trade 订单恢复距上次完成时间，以及专用调度器的活跃线程、队列和池大小。
- M6 秒杀处理中数量、最老年龄、完成速率、预计排空时间和 `NEEDS_ATTENTION` 数量。

首批告警按业务语义区分：

| 告警 | 条件摘要 | 等级 |
| --- | --- | --- |
| 服务不可采集 | 连续 1 分钟 `up == 0` | critical |
| Outbox 积压 | 未发布超过 50 条且持续 2 分钟 | warning |
| Outbox 卡住 | 最老事件超过 5 分钟且持续 2 分钟 | critical |
| 消费需人工处理 | `needs_attention > 0` 持续 30 秒 | critical |
| 消费恢复延迟 | 最老活动失败超过 5 分钟 | warning |
| 所有者域对账异常 | 任一服务未关闭问题持续 30 秒 | critical |
| 受治理同步依赖熔断开启 | 任一已纳管熔断器持续 `OPEN` 30 秒 | critical |
| 同步调用被拒绝 | 5 分钟内出现熔断或舱壁拒绝 | warning |
| 订单恢复调度延迟 | 距上次完成超过 15 秒并持续 1 分钟 | warning |
| 业务失败态 | 支付异常、退款失败或需人工处理持续 30 秒 | critical |
| 系统中间态老化 | 预占、取消、退款处理中超过 5 分钟 | warning |
| 顾客退货等待老化 | 售后等待类状态超过 1 天 | warning |
| 秒杀队列停止排空 | 有处理中请求且两分钟完成速率为 0 | critical |
| 秒杀队列老化 | 最老处理中请求超过 2 分钟 | warning |
| 秒杀需人工处理 | `NEEDS_ATTENTION > 0` 持续 30 秒 | critical |

顾客尚未寄回与系统补偿失败不是同一种异常，因此等待退货使用一天阈值，系统收敛状态使用分钟级阈值。阈值是本地开发初值，后续必须根据容量基线和真实业务 SLO 调整。

## 5. 启动与验证

先按网络文档确认 Clash、Docker 和七个核心中间件容器健康，再初始化凭据：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\deploy\docker
.\bootstrap-resources.ps1
docker compose --env-file .env --profile observability up -d
```

默认本地入口：

- Prometheus：`http://127.0.0.1:19090`
- Alertmanager：`http://127.0.0.1:19093`
- Grafana：`http://127.0.0.1:13000`
- Tempo 查询 API：`http://127.0.0.1:13200`
- Tempo OTLP/HTTP：`http://127.0.0.1:14318`

Grafana 用户名为 `plainjournal-admin`，密码在被忽略的 `.env` 中。不要把该密码复制到文档、截图或测试日志。

当五个核心 Java 服务已在 `18103` 至 `18107` 运行时，可执行：

```powershell
.\verify-observability.ps1
```

脚本验证匿名拒绝、专用采集凭据、Prometheus 与 Alertmanager 配置、五个真实目标、四组告警规则、Alertmanager 连接、Tempo 就绪，以及 Grafana 的 Prometheus/Tempo 数据源和看板预配置。默认只清理本次新启动的观测容器，不删除 D 盘时序与 trace 数据；使用 `-KeepRunning` 可保留它们。

脚本显式使用自身目录中的 Compose 文件、项目目录和 `.env`，因此可从仓库其他目录调用，不依赖当前工作目录。2026-07-22 的最终复验使用官方 `grafana/grafana:13.1.0` 与 `grafana/tempo:2.10.5` 镜像；Prometheus 配置、4 个规则组共 16 条规则、五个实时目标、Alertmanager 路由、Tempo 就绪、Grafana 双数据源和看板全部通过，本机无需为此修改全局镜像源。

M8.8 的 Notification 已复用相同的受保护 Prometheus 身份和
`/actuator/consumerfailures` 原始载荷隐藏规则；专项真实脚本验证了毒消息摘要不泄露
`raw_payload`。当前固定观测栈仍保持五个交易目标和 16 条规则，尚未把 Notification
加入 Grafana 看板或新增邮件积压告警，因此不能把专项端点验证描述为观测栈已经扩容。

M8.9 的 Fulfillment 注册低基数计数器
`ecommerce.fulfillment.geo.cache.operations`，标签只包含 `operation` 和 `outcome`，
用于区分缓存命中、MySQL 回退、提交后写入、读修复与重建结果；订单号、履约号、
用户 ID 和坐标均不进入标签。Fulfillment 本来就是固定五个采集目标之一，但当前
`PlainJournal Operations` 看板和 16 条规则尚未增加 GEO 专属面板或告警，所以本批只
确认应用指标契约，不把它描述为固定观测栈已经完成 GEO 可视化扩容。详见
[M8 第九批：Fulfillment 物流 GEO 与可重建 Redis 投影](64-m8-fulfillment-geo.md)。

M8.12 的 Analytics 注册低基数事件接受/重复、对账问题和重建结果指标，并复用专用
`X-Metrics-Token` 采集身份。真实脚本已验证匿名拒绝、专用身份 200，以及事件、
重建和对账三组指标存在。订单号、用户 ID、事件 ID、商品 ID 和错误正文均不进入
标签。当前固定 Prometheus 配置仍只抓取五个交易核心服务，尚未把 Analytics 加入
常驻目标、Grafana 面板或告警规则，因此本批只声明应用指标契约和专项采集通过。
详见 [M8 第十二批：运营统计事件读模型、对账与审计重建](67-m8-operational-analytics.md)。

完整八服务冒烟可附加观测验证：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\run-foundation-smoke.ps1 -EnableObservability
```

若同时验证 Payment→Trade 的真实跨进程 trace：

```powershell
.\run-foundation-smoke.ps1 -EnableObservability -EnableDistributedTracing
```

追踪导出默认关闭；该开关只在本次脚本进程中设置 OTLP 端点和 100% 采样，并恢复脚本启动前的容器状态。传播细节、失败语义和证据见 [Payment 到 Trade 分布式追踪代表链路](24-distributed-tracing.md)。

## 6. 明确不做

- 不把 `/actuator/prometheus` 暴露到 Gateway 公网路由。
- 不在指标标签中加入订单号、用户 ID、消息 ID 或错误正文等高基数/敏感值。
- 不用 Grafana 或 Alertmanager 直接修改业务库、重放消息或推进状态机。
- 不在本批引入 Loki、SkyWalking、ELK 或 OpenTelemetry Collector；追踪只保留 Micrometer Tracing、一个 OpenTelemetry bridge 和一个 Tempo 主后端。
- 不把 Payment→Trade 代表链路描述成八服务全链路完成；其余 HTTP、消息和补偿边界必须逐批补证据。
- 不把本地空接收器描述成已经完成外部值班通知；生产通知渠道必须另行配置和验证。
