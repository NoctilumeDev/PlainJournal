# 32 GiB 扩展验收协议

> 状态：`PLANNED / DEFERRED`  
> 适用对象：PlainJournal M0-M8 冻结基线  
> 执行条件：32 GiB 或更多物理内存的 Windows 主机  
> 当前结论：本协议尚未执行，不构成新的容量、三实例或故障恢复证据  
> 执行准备度：现有 runner 可直接执行；文中明确标为待补的 readback runner、峰值采样器和精确拓扑仍是正式验收前置，不得假装已经具备

## 1. 为什么存在这份协议

本轮归档复验在 16 GiB 宿主机上完成了源码、自动化、前端和中间件分层检查，但在七个
核心中间件与八个业务 JVM 同时存在时触发了宿主机容量停止线。Core Smoke 没有在资源
条件不足时继续制造绿色结果，因此该现场应记为：

```text
INCONCLUSIVE / HOST CAPACITY BOUNDARY
```

它不是 PlainJournal 的产品失败，也不是 32 GiB 环境已经通过。升级物理内存以后，按本
协议从固定提交重新执行；不要沿用本轮工作区、容器、构建物或口头结论。

### 1.1 三条证据线不能合并

16 GiB 与未来双通道 32 GiB 是两个不同的宿主条件，必须并列保存，不能用后者覆盖前者：

| 证据线 | 能写什么 | 不能写什么 |
| --- | --- | --- |
| 既有 16 GiB 发布证据 | 只保留当时固定 SHA、分组拓扑、1000 请求/100 并发和三实例等已经实际执行的历史结论 | 不能外推为本轮 fresh 环境仍能容纳完整拓扑 |
| 本轮 16 GiB fresh 复验 | 源码/门禁/前端/中间件分层结果，以及完整拓扑前触发的 `INCONCLUSIVE / HOST CAPACITY BOUNDARY` | 不能写成产品 FAIL，也不能因未来 32 GiB 通过而删除该停止现场 |
| 未来双通道 32 GiB 复验 | 只能写本协议在新宿主、固定 SHA 和实际完成阶段得到的新增事实 | 不能反向改写 16 GiB 的资源鸿沟，也不能把未执行阶段补成 PASS |

未来报告必须分别给出三条记录的硬件、SHA、拓扑、时间、状态和证据路径。32 GiB 若通过，
正确叙述是“在增加到双通道 32 GiB 后取得了新的完整证据”，不是“原 16 GiB 失败已经
消失”。若 32 GiB 仍触发停止线，也保留为新的宿主边界。

### 1.2 本轮 16 GiB fresh 停止现场

本轮以公开提交 `a4fecf46e918278e625c1815b16ff6fe2656ffc7` 为代码身份，并仅在 ignored
`.env` 中替换已经证明非法的 Nacos 占位 token，以便继续定位宿主容量；因此它不能冒充
“原公开模板无需修复即可 fresh bootstrap”。现场命令读回记录了以下序列：

| 时点 | 直接观察 | 能得出的结论 |
| --- | --- | --- |
| 七个核心中间件与 bootstrap 完成后 | 容器均为 `RestartCount=0`、`OOMKilled=false`；宿主可用物理内存约 `3.60 GiB` | 中间件和资源初始化能在该宿主分层成立 |
| 八个业务 JVM 全部监听后 | `8/8` 服务端口存在；宿主仅余 `0.46 GiB`，物理内存使用率约 `97.1%` | 完整拓扑能够启动，但已越过安全运行余量，不能继续制造业务 PASS |
| 触发停止并中断后 | 仓库业务 JVM 与八个端口均归零，可用物理内存回升至约 `4.28 GiB` | 宿主容量是本次停止变量；这仍不证明交易链正确 |
| 中断后的事实读回 | smoke 临时用户和商品事实仍存在，说明强制中断没有完成脚本 `finally` 的业务清理 | 本轮不能写 Core Smoke PASS，隔离 fresh 数据必须重置后封存现场 |

这组数值来自本轮会话中的直接宿主命令读回，但原始输出没有作为公开、机器可读 artifact
持久保存；因此它足以冻结停止理由和下一轮阈值设计，不足以冒充第三方可独立下载的发布
证据。32 GiB 正式执行必须把相同采样写入带 run ID 的持久证据，并继续保留这里的
`INCONCLUSIVE / HOST CAPACITY BOUNDARY`，不能用新结果覆盖。

本协议的目标依次是：

1. 在默认资源合同下完成 Fresh Bootstrap 与 Core Smoke；
2. 证明代表服务的三实例正确性，而不是把所有服务机械扩成三份；
3. 通过资源有界的并发阶梯找到当前机器的真实容量边界；
4. 在稳定容量基线之外，单独验证故障、恢复与最终对账；
5. 完成浏览器现实、清理和固定坐标证据闭环。

## 2. 不可改变的裁决规则

- 使用预先批准的完整 Git SHA；不得以会移动的 `main` 代替实验身份。
- 正式证据必须来自 fresh clone、干净工作区和公开可取得的提交。
- 不复用作者工作区的 `target/`、`node_modules/`、`.run/`、`.env` 或中间件数据。
- 阶段串行执行；上一阶段的资源、失败和清理未闭环，不进入下一阶段。
- 首次资源或正确性停止线出现后，保存证据并停止升压，不调低断言换取更大的数字。
- 页面文件只作为系统缓冲；持续换页、OOM、容器重启或宿主失稳不能算性能通过。
- `-SkipNetworkPreflight` 只在人工完成并记录等价检查时使用；正式首轮默认不跳过。
- `-SkipBuild` 只在构建物 SHA 与当前源码身份已被独立证明时使用；fresh 首轮不使用。
- 容量模式和故障模式分开，不能同时改变并发、实例数和基础设施可用性。
- API 成功不等于业务通过；数据库、Outbox、MQ、对账与清理必须同时成立。
- `docker compose down -v`、通配符删除卷或删除 `MIDDLEWARE_DATA_ROOT` 均不属于本协议。

## 3. 阶段 0：固定身份与全新工作区

将 `<approved-full-sha>` 替换为统一归档裁决后批准的 40 位提交 SHA。克隆目录必须与日常
开发目录分离；示例路径只是占位符，不是机器专属合同。

```powershell
$targetCommit = '<approved-full-sha>'
$freshRoot = '<fresh-root>'
git clone https://github.com/NoctilumeDev/PlainJournal.git $freshRoot
Set-Location $freshRoot
git checkout --detach $targetCommit

$actualCommit = (git rev-parse HEAD).Trim()
if ($actualCommit -ne $targetCommit) { throw "Unexpected commit: $actualCommit" }
if (git status --porcelain) { throw 'Fresh worktree is not clean.' }
$repositoryRoot = (Get-Location).Path
```

记录但不发布秘密值：

- Windows、WSL、Docker Desktop、Docker Engine 与 Compose 版本；
- CPU 型号、逻辑核数、DIMM 数量/容量、通道模式、物理内存总量、页文件策略和可用内存；
- JDK、Maven CLI、Maven Wrapper、Node.js、pnpm 与 Chromium 版本；
- 固定 SHA、远端 URL、执行开始时间和 Docker Desktop 启动入口；
- Docker/WSL 空载时的进程、容器、端口与内存快照。

若 Docker Desktop 从某个启动上下文出现 Windows socket 初始化错误，应保留诊断并换用
已验证可用的桌面或 Explorer 入口重试。一次启动差异只记录为宿主交互边界，不直接升级
为已确认的 Docker 根因。

## 4. 阶段 1：宿主机预算与停止线

### 4.1 先测量，再分配

32 GiB 不是“所有组件都能无限增长”的许可。第一次运行保持仓库默认 JVM 与 Compose
合同，不为了挤进内存先做小堆特调。每个阶段至少在开始、拓扑稳定、流量峰值和清理后
记录：

- 宿主总物理内存、可用物理内存和使用率；
- Docker/WSL、每个容器和每个 Java 进程的内存；
- 容器 `RestartCount`、`OOMKilled`、健康状态和实际挂载；
- TCP/UDP 动态端口占用及近期 Windows `4231/4266` 事件；
- JVM heap、GC、线程和连接池；
- MySQL 连接、RocketMQ backlog、Outbox pending/oldest age。

仓库默认预检要求可用内存至少 3 GiB，且物理内存使用率低于 82%。在标称 32 GiB 主机
上，82% 规则通常比 3 GiB 更严格，相当于约 5.8 GiB 的余量；以脚本实际读取到的总内存
和更严格条件为准，不把换算值写成跨机器常量。

```powershell
Set-Location (Join-Path $repositoryRoot 'backend')
./tools/check-verification-host.ps1 -SkipDocker
```

建议把不少于约 6 GiB 的物理内存视为不可消费的动态储备，而不是把 32 GiB 全部分给
Docker 和 JVM。Windows、Docker/WSL、核心中间件、业务 JVM、负载生成器和浏览器分别
记账；具体预算以首次稳定观测为准。若需要收窄某一组件，先形成单独假设和 A/B 证据，
不得把临时低内存参数冒充默认产品合同。

### 4.2 硬停止条件

任一条件成立即保存现场、停止当前级别并清理，不进入下一档：

- 仓库宿主预检失败；
- 可用物理内存或使用率越过预检停止线；
- 发生 OOM、非预期容器/JVM 重启或持续明显换页；
- 动态端口超限或出现新的 `4231/4266`；
- HTTP/传输错误、数据库事实、幂等、库存或对账断言失败；
- 清理无法证明只处理本次运行拥有的资源。

## 5. 阶段 2：Fresh Bootstrap

### 5.1 生成本机环境并解析 Compose

```powershell
Set-Location (Join-Path $repositoryRoot 'deploy/docker')
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
./bootstrap-resources.ps1 -PrepareEnvironmentOnly
docker compose --env-file .env --profile core config --quiet
docker compose --env-file .env --profile core pull
```

正式运行前读取 `.env` 中的 `COMPOSE_PROJECT_NAME` 和 `MIDDLEWARE_DATA_ROOT`，解析后记录
实际绝对数据目录。不得打印 `.env`、Nacos 原始启动环境或任何 secret 值。

Fresh clone 并不自动带来 fresh Docker 存储。当前 Compose 除 bind mount 外，还使用
固定全局卷 `plainjournal-rocketmq-broker-store` 和固定网络 `plainjournal-network`。
在中间件隔离前执行只读盘点：

```powershell
$coreVolume = 'plainjournal-rocketmq-broker-store'
$coreNetwork = 'plainjournal-network'
$existingVolume = @(docker volume ls --format '{{.Name}}' | Where-Object { $_ -eq $coreVolume })
$existingNetwork = @(docker network ls --format '{{.Name}}' | Where-Object { $_ -eq $coreNetwork })
if ($existingVolume.Count -ne 0 -or $existingNetwork.Count -ne 0) {
  docker volume inspect $coreVolume 2>$null
  docker network inspect $coreNetwork 2>$null
  throw 'Fresh Docker storage/network precondition failed; inspect ownership and stop.'
}
```

同时解析 `MIDDLEWARE_DATA_ROOT` 的实际绝对路径；正式 fresh 运行要求它是本次专用的空
目录。只要固定卷、固定网络或数据目录已有内容，就停止并人工裁决所有权，不在本协议中
自动删除、改 ACL 或覆盖。把“运行前不存在”记录进证据，供最终清理判断资源是否确由
本轮创建。

检查目标是“当前脚本声明的资源集合”，而不是只相信固定数量：

- 每个 owner schema 及其 least-scope 用户；
- `deploy/docker/nacos/` 中的全部配置文件；
- README 声明的去重后 RocketMQ topic 集合；
- README 声明的全部 MinIO private bucket；
- bootstrap 声明的每个本机生成 secret，验证存在性、格式和相互隔离，不输出内容。

当前 `bootstrap-resources.ps1` 对这些资源执行创建/更新并检查调用成功，但仓库尚无一个统一
runner 对 Nacos 每份配置的最终内容、去重后的 RocketMQ topic 集合、MinIO bucket 与 ILM
做脱敏只读比较。正式 32 GiB Fresh Bootstrap 前必须先批准该 readback runner，要求：

- 预期集合直接来自当前源码/README，不手抄另一份会漂移的清单；
- Nacos 逐个读取并比较内容身份，不把 POST 成功当成最终读回；
- RocketMQ 比较必需 topic 为预期子集，同时区分系统 topic；
- MinIO 比较 private bucket 及 `chat-attachments` quarantine ILM；
- 输出只含名称、哈希、数量和判定，不落地 token、密码或配置秘密。

该 runner 尚未存在时，可以继续做隔离和 Core Smoke 的开发性复验，但 Fresh Bootstrap 的
正式状态保持 `PLANNED / DEFERRED`，不得仅凭 bootstrap 的成功输出写 PASS。

### 5.2 先做中间件隔离

隔离脚本要求目标容器事先不存在，并会对 MySQL、Redis、Nacos、RocketMQ 和 MinIO 逐项
启动、探测、采样和删除。因此它必须在完整 Core 拓扑之前执行，不能在七容器已经存在时
调用：

```powershell
Set-Location (Join-Path $repositoryRoot 'backend')
./tools/verify-middleware-isolation.ps1
```

隔离证据必须显示每个阶段的基本读写/连接成立，且该阶段拥有的容器已经清理。若存在任何
预先存在的同名容器，先查明所有权，不得强删后重跑。

### 5.3 再组装完整七容器

保持 Compose 定义不变，串行观察依赖链；每一步都记录 `docker compose ps`、
`docker stats --no-stream`、健康状态、`RestartCount`、`OOMKilled` 和宿主预检结果。

```powershell
Set-Location (Join-Path $repositoryRoot 'deploy/docker')
docker compose --env-file .env --profile core up -d mysql
docker compose --env-file .env --profile core up -d redis
docker compose --env-file .env --profile core up -d nacos
docker compose --env-file .env --profile core up -d rocketmq-proxy
docker compose --env-file .env --profile core up -d minio
docker compose --env-file .env --profile core ps
./bootstrap-resources.ps1

Set-Location (Join-Path $repositoryRoot 'backend')
./tools/check-verification-host.ps1 -RequiredContainers @(
  'plainjournal-mysql',
  'plainjournal-redis',
  'plainjournal-nacos',
  'plainjournal-rocketmq-namesrv',
  'plainjournal-rocketmq-broker',
  'plainjournal-rocketmq-proxy',
  'plainjournal-minio'
)
./tools/verify-pre-m9-database-ownership.ps1 `
  -OutputPath 'backend/.run/32gib-owner-database-readback.json'
```

Fresh Bootstrap 只有在数据库 ownership runner、待补的资源 readback runner、容器状态
和宿主门禁共同匹配时通过。脚本打印“ready”不能替代 MySQL、Nacos、RocketMQ 和 MinIO
的独立读回。

## 6. 阶段 3：默认 Core Smoke

先恢复七个核心容器健康并再次执行宿主预检；随后使用默认合同，不传小堆、跳过构建或
放宽清理参数：

```powershell
Set-Location (Join-Path $repositoryRoot 'backend')
./tools/verify-core-smoke.ps1
```

这一阶段必须真实证明：

- Gateway、Identity、Catalog、Inventory、Trade、Payment、Fulfillment、Marketing
  八个 JVM 同时在线；
- 注册登录、商品、库存、营销、下单、支付、履约、取消与结果未知恢复；
- 每个 owner DB 的最终事实、Outbox 发布、MQ 消费、幂等和跨域对账；
- 库存方程成立，无重复支付/退款/履约副作用，无无法解释的永久处理中记录；
- `finally` 清理完成后，业务 JVM、业务端口和 run-scoped 数据归零。

若该阶段仅因宿主资源门禁停止，状态仍是 `INCONCLUSIVE / HOST CAPACITY BOUNDARY`；若
资源满足而业务不变量失败，才是产品或验证实现 finding，必须先定位根因。

## 7. 阶段 4：代表服务三实例

“三实例”用于验证有竞争意义的代表边界，不等于全部服务乘三。按下列批次串行执行，
每批完成清理和最终事实读回后再进入下一批。

当前设计矩阵冻结了三类目标拓扑：

| 目标 | 代表拓扑 | 必须证明的事实 |
| --- | --- | --- |
| 交易竞争 | Trade ×3、Inventory ×3，其余 ×1 | 数据库竞争、Outbox/租约、库存非负与单一副作用 |
| 消息竞争 | Payment ×3、Fulfillment ×3，其余 ×1 | 消费组竞争、重复投递、ACK 边界和幂等 |
| 发布治理 | Gateway ×3、代表业务服务 ×3，其余 ×1 | 负载分散、摘流、优雅退出、滚动升级与回退 |

当前仓库的专用 runner 直接覆盖 Trade 1/2/3 发布者、Trade 容器、Trade 的
`PaymentSucceeded` 消费竞争，以及一个 Gateway 下的 Trade stable/candidate 滚动路径。
这不能自动升级成 Inventory ×3、Payment ×3、Fulfillment ×3 或 Gateway ×3 已通过。
32 GiB 执行前应为仍缺的精确拓扑批准一个仓库内 runner，或把该项保持
`PLANNED / DEFERRED`；不得用相邻实验替代。

### 7.1 Trade Outbox 发布者竞争

该 runner 使用固定 topic `plainjournal-m3-outbox-probe-v1`，并清理所有
`aggregate_type LIKE 'M3OutboxProbe:%'` 的探针行。运行前必须用 RocketMQ topic list 和
Trade owner 账号分别证明固定 topic 与该前缀行都不存在；任一已存在就停止，不能让
runner 把前次或并行实验的残留当成本轮资源删除。

```powershell
Set-Location (Join-Path $repositoryRoot 'backend')
./verify-trade-outbox-multi-instance.ps1
```

要求 1/2/3 实例各自真实运行；三实例批次中每个实例都参与；同聚合事件顺序、claim/lease、
重试和最终 `PUBLISHED` 收敛成立。现有脚本插入一个带短租约的模拟 dead-owner claim，
证明过期 claim 被仍在线的发布者恢复；它没有真实停止一个发布者。

“三实例流量中停止一个实例、其余实例继续发布并接管”保持 `PLANNED / DEFERRED`，直到
仓库内 runner 能在进程退出后继续注入事件并核对单一副作用。

### 7.2 Trade 容器、Nacos 身份与优雅退出

该 runner 使用固定 topic `plainjournal-m3-container-probe-v1`，并在运行前/后清理所有
`aggregate_type LIKE 'M3ContainerProbe:%'` 的探针行。命令执行前同样要求固定 topic 与
该前缀行都不存在；存在即停止并裁决所有权。

```powershell
./verify-trade-container-multi-instance.ps1 `
  -EventCount 1000 `
  -TimeoutSeconds 180
```

要求三个容器同时在线、Nacos 注册身份不同、负载确实分散、所有实例参与；完整负载和
顺序断言结束后，一个实例接受优雅停止并从 Nacos 注销。现有脚本没有在停止后继续发流量，
因此该结果不能代替上一节延期的真实退出后接管实验。

### 7.3 PaymentSucceeded 消费竞争与进程终止

```powershell
./verify-trade-consumer-multi-instance.ps1 `
  -EventCount 1000 `
  -TimeoutSeconds 300
```

使用脚本默认的隔离 RocketMQ。必须证明三实例消费参与、重复投递不增加业务副作用，并
覆盖 `OUTBOX_BEFORE_PUBLISH`、`OUTBOX_AFTER_BROKER_ACK`、
`CONSUMER_AFTER_COMMIT` 三个边界的退出与恢复。

### 7.4 Gateway/Trade 滚动升级与回退

```powershell
./verify-gateway-rolling-upgrade.ps1 `
  -TimeoutSeconds 300 `
  -ProbeIntervalMilliseconds 100
```

要求 stable/candidate 实例可区分，请求真实落到预期实例；失败 candidate 不进入服务发现；
旧实例先摘流再 SIGTERM；滚动过程中业务成功、最终 release 身份和临时网络清理闭合。

三实例阶段的退出标准不是“进程数量达到三”，而是：三个实例真实参与、每个权威副作用
仍只有一次、单实例退出和重新加入不会改变最终事实。只有表中三类目标拓扑都由对应证据
覆盖，才能写“代表服务三实例完成”；否则必须逐项标注已通过和延期部分。

## 8. 阶段 5：并发正确性阶梯与容量测定

### 8.1 仓库现有的正确性阶梯

先保持七个核心容器健康、工作区干净，然后执行仓库现有的并发正确性编排入口：

```powershell
Set-Location (Join-Path $repositoryRoot 'backend')
./tools/verify-foundation-capacity-ladder.ps1
```

它依次运行：

```text
1 -> 10 -> 50 -> 100 -> 300 -> 500 -> 1000
```

默认 `RequestsPerLevel=1000` 的实际含义是**每个容量场景**有 1000 个请求：Inventory
竞争、Trade 竞争、同订单键重放、同支付回调重放和同退款回调重放共五个场景串行执行，
另有建立业务夹具和恢复/对账所需的流程请求。只有最后一级才表示每个容量场景最多有
1000 个请求同时在途；“脚本接受 1000”不等于该级已经通过。每一级必须同时保存：

- 每场景请求数、每场景同时在途请求数、整级汇总、HTTP/传输成功与错误；
- 吞吐、p50、p95、p99 和完整响应记录；
- 宿主内存、Docker/WSL、JVM heap/GC、线程和连接池；
- MySQL 连接、RocketMQ backlog、Outbox pending/oldest age；
- 库存非负与库存方程、幂等唯一、重复回调副作用数；
- 各 owner domain 最终事实、补偿和 reconciliation 结果；
- 阶段后的端口、JVM、临时数据和容器状态。

首次失败级别就是当前 SHA、当前拓扑和当前机器的容量边界。不要硬冲下一档，也不要把
前一档 PASS 外推为后一档 PASS。

现有 ladder 只在每一级前后运行宿主预检；Foundation 证据保存五类负载的延迟、正确性
和部分最终 Outbox 事实。它尚未在负载期间连续采集或强制停止以下指标：容器
`RestartCount/OOMKilled`、宿主峰值、JVM heap/GC/线程/连接池、MySQL 连接、MQ backlog、
Outbox oldest age。因此：

- 现有入口通过，只能写“该并发级别的单轮业务正确性通过，且级别前后宿主门禁通过”；
- 它不能单独证明峰值资源安全、持续容量或中途从越线状态恢复后的资源健康；
- 真正的容量测定保持 `PLANNED / DEFERRED`，直到仓库内增加贯穿每一级的只读采样器和
  越线终止；采样器必须与 workload 同时开始/结束，并把原始时间序列关联到同一 run ID；
- 在采样器完成前，即使 1000 同时在途业务断言通过，也不得更新“32 GiB 容量”公开声明。

### 8.2 持续负载是后续独立实验

默认阶梯主要证明各并发级别的一轮正确性。若每个场景 1000 同时在途已经完整通过，才
可以另开一个 `PLANNED` 的持续负载实验，增加 `requestsPerScenario` 或持续时间。执行前
必须固定：

- 选定的并发级别与请求总数/持续时间；
- 预热、随机种子、流量构成和错误预算；
- 资源采样间隔、允许的 GC/连接/backlog 边界；
- 实验结束后的最终一致性与清理断言。

持续负载不得和故障注入、滚动升级或新增实例同时发生。它尚无本协议下的通过结论，
因此不能预先写入 README 或验证摘要。

## 9. 阶段 6：故障、恢复与对账

只有 Core Smoke、三实例和所选容量基线稳定后才进入本阶段。每条命令单独运行，恢复初始
拓扑并完成清理后再执行下一条；不能把多个 flag 堆在同一次运行中。

| 单变量场景 | 仓库入口 | 当前协议裁决 |
| --- | --- | --- |
| Outbox publish 前、Broker ACK 后、Consumer commit 后进程退出 | `./verify-trade-consumer-multi-instance.ps1 -EventCount 1000 -TimeoutSeconds 300` | 复用阶段 4 的机器可读证据，不重复制造同一实验 |
| Redis 不可用与恢复 | `./run-foundation-smoke.ps1 -EnableRedisFaultInjection` | 执行 |
| Inventory 已执行但响应丢失 | `./run-foundation-smoke.ps1 -EnableInventoryReservationResponseLossFaultInjection` | 执行 |
| Payment 调用 Inventory 确认失败 | `./run-foundation-smoke.ps1 -EnablePaymentInventoryConfirmationFaultInjection` | 执行 |
| Payment→Trade 同步调用熔断与恢复 | `./run-foundation-smoke.ps1 -EnableSynchronousResilienceFaultInjection` | 执行 |
| Trade→Marketing 同步调用熔断与恢复 | `./run-foundation-smoke.ps1 -EnableTradeMarketingResilienceFaultInjection` | 执行 |
| 取消后迟到支付与人工补偿 | `./run-foundation-smoke.ps1 -EnableExceptionalPaymentRecoveryVerification` | 执行 |
| RocketMQ Proxy 端点不可用、Outbox 保留和恢复投递 | `./tools/verify-m6-flash-sale-queue.ps1 -EnableMqFaultInjection` | 执行；Broker 与 NameServer 保持在线 |
| Broker/NameServer 整体停机、任意 MySQL/MQ 网络短断、真实实例退出后继续接管/重加入 | 尚无与本协议完全对应的专用 runner | `PLANNED / DEFERRED`；不得手工断网后补写 PASS |

上述命令均从 `(Join-Path $repositoryRoot 'backend')` 执行，保留脚本输出的 `.run/` 证据。
若现有 runner 的前置、恢复或 `finally` 无法证明本轮资源所有权，就停止并修验证工具，
不能直接对共享容器做裸 `stop`、断网或数据删除。

每次实验都必须记录故障注入点、实际发生时间、恢复动作、重复投递次数和最终副作用数。
恢复不是“服务又健康了”，而是同时满足：

- owner DB 的权威事实闭合；
- Outbox 没有异常悬挂；
- MQ backlog 回到可解释的稳定状态；
- 幂等事实仍唯一；
- inventory、trade、payment、fulfillment、refund、marketing 的对账守恒；
- 未恢复对象进入明确人工治理状态，而不是被静默忽略。

## 10. 阶段 7：浏览器与外部现实

前面各 runner 默认会在 `finally` 停止业务 JVM，不能假定走到本阶段时后端仍在线。仓库
现有 `verify-frontend-order-payment-workspace.ps1` 会重新 bootstrap、打包并启动八个业务
JVM、真实 storefront 和响应丢失代理，在保持窗口内允许真实浏览器操作，最后清理其进程。

Fresh clone 先安装冻结的前端依赖，再串行运行 Payment 与 Fulfillment 两个工作台：

```powershell
Set-Location (Join-Path $repositoryRoot 'frontend')
corepack enable
corepack prepare pnpm@11.9.0 --activate
pnpm install --frozen-lockfile

Set-Location (Join-Path $repositoryRoot 'backend')
./tools/verify-frontend-order-payment-workspace.ps1 `
  -Scenario Payment `
  -BrowserHoldSeconds 600
./tools/verify-frontend-order-payment-workspace.ps1 `
  -Scenario Fulfillment `
  -BrowserHoldSeconds 600
```

使用脚本输出的 `WORKSPACE_READY` 地址和本轮夹具，不使用旧账号或旧 URL；按终端协议完成
浏览器动作后，让脚本自身进入 `finally`。若脚本未能提供足够保持时间、夹具或继续信号，
状态是验证入口 finding，不手工留下常驻 JVM 代替。

浏览器地址、Origin 和文档合同必须一致；`localhost` 与 `127.0.0.1` 在浏览器安全模型中
不能互换。

验收至少包括：

- 桌面与 390 px 移动视口；
- F12 Network 的请求、状态码、重试与响应；
- Console 无未解释错误；
- 页面可见事实与 API、owner DB、Outbox/MQ 最终事实一致；
- 自动化输入后，在提交前从页面和 Network 双重确认真实表单值与 Origin。

浏览器控制工具、代理、错误 Origin 或宿主资源都可能成为污染变量。出现失败时先分类为
Product、Validation Tool 或 Host finding，再决定是否修改项目。

## 11. 阶段 8：清理与反污染读回

### 11.1 清理前先确认所有权

PlainJournal 核心 Compose 同时使用 `MIDDLEWARE_DATA_ROOT` 下的 bind mount 和固定名
RocketMQ 卷 `plainjournal-rocketmq-broker-store`，不是“三只受保护的 PlainJournal 卷”。
任何清理前必须读回：

- `.env` 中解析后的 `COMPOSE_PROJECT_NAME` 与 `MIDDLEWARE_DATA_ROOT`；
- `docker inspect` 中每个容器的实际 mount source/destination；
- 固定 RocketMQ 卷的 Compose labels、挂载容器和运行前是否存在的证据；
- 本轮脚本创建的容器、网络、topic、consumer group、schema/rows、对象和临时文件；
- 是否存在不在本轮 allowlist 内的未知资源。

出现一个未知 mount 或未知所有者就停止。不得按名称猜测可删除性。

### 11.2 允许的清理边界

- 优先让仓库脚本的 `finally` 清理其 run-scoped 资源；
- 7.1/7.2 的固定 topic 与固定探针前缀必须在各 runner 启动前确认不存在；阶段 11 的
  事后盘点不能追回已经被 runner 扩大范围清掉的旧数据；
- Core 容器用下列命令停止，不删除 bind-mounted 数据：

```powershell
Set-Location (Join-Path $repositoryRoot 'deploy/docker')
docker compose --env-file .env --profile core down
```

- 只删除脚本明确创建并带本轮 token/label 的临时网络或卷；
- Consumer 三实例脚本拥有的隔离 RocketMQ 卷必须与本轮精确名称一致后才可删除；
- 固定 Core RocketMQ 卷只有在“运行前不存在”、Compose labels 完全匹配、所有容器已停且
  没有挂载者四项证据同时成立时，才可在最终读回后删除精确卷名；否则保留并报告边界；
- 不删除 `.env`、`.runtime-secrets` 或中间件数据，除非本次实验明确使用全新可丢弃目录，
  且清理前已再次核对绝对路径和所有权。

### 11.3 最终读回

最终必须同时确认：

- 业务 JVM 为 0，业务端口无监听；
- run-scoped 容器、网络、topic、consumer group、临时 schema/rows 和对象无残留；
- 固定 `plainjournal-network` 已按本轮运行前快照恢复；固定 RocketMQ 卷已按上一节的
  所有权裁决删除或明确保留；
- 本轮没有留下 OOM/restart 未解释状态；
- 原始 bind-mounted 数据目录仍在且未被扩大范围删除；
- Git 工作区干净，HEAD 仍等于固定 SHA；
- 证据目录不含 JWT、内部 header、`.env` 内容或其他 secret。

## 12. 状态与公开基线

每个阶段只能使用以下状态之一：

| 状态 | 含义 |
| --- | --- |
| `PASS` | 固定 SHA、声明拓扑、业务事实和清理全部通过 |
| `FAIL` | 在满足前置条件时出现已证明的产品或验证实现缺陷 |
| `INCONCLUSIVE / HOST CAPACITY BOUNDARY` | 宿主前置条件不足，未取得业务结论 |
| `PASS WITH BOUNDARY` | 已证明范围通过，未验证范围被明确写出 |
| `ACCEPTED BOUNDARY` | 边界真实存在且与当前合同一致，不通过扩权施工消除 |
| `PLANNED / DEFERRED` | 协议已定义但尚未执行 |

只有以下条件全部成立，才能更新公开验证摘要中的容量或 32 GiB 结论：

1. 固定公开 SHA 可取得；
2. fresh clone 和工作区干净；
3. 原始日志、机器可读证据、owner DB/MQ 读回和资源快照完整；
4. 失败、恢复与停止点没有被删除；
5. 最终清理和 Git 身份读回通过；
6. 结论只覆盖实际执行的实例数、并发、持续时间、数据量和故障模型。

更新时只能追加 32 GiB 证据坐标，并继续保留既有 16 GiB 历史证据和本轮 fresh 宿主停止
记录；不得用一条笼统的“更大内存已通过”替换三者。原始失败日志、停止线快照和清理读回
不得从归档证据中删除。

在此之前，本文件冻结的是执行顺序、停止线、现有入口和待补前置，不是成功声明，也不是
已经完全自动化的一键验收脚本。标为 `PLANNED / DEFERRED` 的支撑项必须先实现并独立批准，
才能把对应阶段从开发性复验升级为正式证据。
