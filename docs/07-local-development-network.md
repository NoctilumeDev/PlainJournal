# 本地开发网络基线

## 1. 本机实际拓扑

- 物理以太网持有系统默认路由，网段为 `192.168.1.0/24`。
- Clash Verge 2.5.1 关闭 TUN，使用 Windows 系统代理 `127.0.0.1:7897`。
- WSL 2 使用 mirrored networking、DNS tunneling 和 auto proxy。
- Docker Desktop 使用 Windows 系统代理，内部网段为 `192.168.65.0/24`。
- VMware 使用 VMnet8 NAT，网段为 `192.168.15.0/24`，不使用桥接。

三个网段互不重叠，当前只有物理以太网提供默认路由。此机器不存在需要置顶的 VPN/TUN 适配器，因此不使用旧式网卡排序，也不盲目修改接口跃点数。

## 2. 已落地配置

`C:/Users/lenovo/.wslconfig`：

```ini
[wsl2]
networkingMode=mirrored
dnsTunneling=true
autoProxy=true
firewall=true

[experimental]
bestEffortDnsParsing=true
```

这会让 WSL 同步 Windows 的网络、DNS 和代理信息。`autoProxy` 会为 WSL 环境提供代理配置；Docker Desktop 同时通过自己的系统代理设置转发镜像拉取和容器出站流量。不能把它表述成“给所有容器强制注入代理变量”，应用容器是否读取 `HTTP_PROXY` 仍取决于其启动配置和程序行为。

## 3. 故障结论

多次表现为“网络断开”的故障并不是同一个根因。已经通过 Windows
网络状态、事件日志、Clash 日志、真实 HTTP 请求和容器检查区分出以下四类：

| 故障层级 | 已观察到的证据 | 当前结论 |
| --- | --- | --- |
| 代理或上游节点 | `127.0.0.1:7897` 仍监听，但真实代理请求超时；Clash 记录上游连接超时 | 监听端口存在不代表代理链路可用。应先切换健康节点或重载 Clash 核心，不应改系统路由 |
| WSL/Docker 状态 | 宿主机直连和代理已恢复，但 WSL 或容器仍持有旧 DNS/代理状态 | 使用既有 WSL/Docker 重载脚本恢复；不删除 Docker 数据，不硬改 WSL DNS |
| 物理网卡或网络状态重建 | 禁用再启用以太网后重新取得 DHCP 租约，随后网络恢复 | 网卡重置会同时重建链路、DHCP、NCSI、WinINet 和代理网络状态，因此“重置后恢复”只能证明状态重建有效，不能单独证明物理网卡或驱动是根因 |
| 动态端口耗尽 | Windows System 日志多次出现 TCP `4231` 和 UDP `4266`，表示临时端口分配失败 | 高并发或大量短连接可以让宿主机表现为普遍连接失败。重启或连接回收后无法再追溯原始占用进程，因此必须在故障发生时采集；不能把任何一次 Codex 退出或代理超时自动归因给历史 `4231` |

最初的超时不是已证实的“多默认路由争抢”。采集结果显示网段没有重叠、
默认路由只有一条。Docker 未启动也不会导致宿主机自身断网。

因此应把 VMware、Docker、WSL、Clash 和物理网卡驱动同时工作视为可能增加
启动时序和过滤链复杂度的条件，而不是把这些软件直接判定为“争抢网卡”。
RocketMQ 端口映射错误会影响 RocketMQ 客户端，但不能解释宿主机 DNS、网关或
普通 HTTPS 同时失败。

## 4. 启动与诊断

建议启动顺序：

1. 启动 Clash Verge，确认 `127.0.0.1:7897` 可实际代理请求。
2. 启动 Docker Desktop，等待 `docker info` 成功。
3. 需要虚拟机时再启动 VMware，并保持 VMnet8 NAT。

诊断命令：

```powershell
& 'D:\DevTools\Network\check-dev-network.ps1'
```

默认模式是项目真实冒烟前置门禁，要求 Docker 和七个核心中间件已经运行。
刚开机、Docker 尚未启动，或只诊断宿主机时使用：

```powershell
& 'D:\DevTools\Network\check-dev-network.ps1' -SkipDocker
```

宿主机模式仍会检查：

- Realtek 物理以太网是否为 `Up`；
- 是否存在非 `169.254.0.0/16` 的可用 IPv4；
- 是否只有一条经 `192.168.1.1` 的以太网默认路由；
- 网关、Windows DNS、宿主机直连 HTTPS 是否真实可用；
- Clash 是否监听，以及真实代理 HTTPS 是否成功；
- TCP/UDP 动态端口当前占用是否低于 80%；
- 最近 15 分钟是否出现 TCP `4231` 或 UDP `4266` 端口耗尽事件。

诊断结果按以下顺序解释：

1. 网卡、IPv4、默认路由或网关失败：先保留输出，再检查网线、路由器、
   Realtek 网卡和 DHCP。此时不要先改 Clash、Docker 或 Maven 配置。
2. 网关成功，但 DNS 或宿主机直连失败：检查路由器 DNS、Windows DNS 和物理
   出站链路。
3. 宿主机直连成功，但真实 Clash 请求失败：问题位于 Clash 核心、代理节点或
   上游链路。端口仍监听不能判定成功。
4. 宿主机全部成功，只有 Docker 或容器失败：再处理 WSL/Docker 的 DNS 和代理
   状态。
5. 动态端口检查失败：停止新的压测或高连接任务，立即采集 TCP/UDP 状态和
   占用进程；不把扩大端口范围作为第一反应。

重载 WSL 和 Docker 网络：

```powershell
& 'D:\DevTools\Network\restart-wsl-docker.ps1'
```

脚本及机器级说明保存在 `D:/DevTools/Network`。该方案没有删除网卡、修改系统路由或迁移 Docker 数据。

禁用再启用物理以太网会中断 Codex、浏览器、代理、WSL、容器和所有活动连接。
只能在宿主机网关、DNS 或直连已经失败，且诊断输出已经保留后执行；不能把它
作为普通项目脚本的自动恢复动作。

## 5. RocketMQ 端口经验

RocketMQ 5.x gRPC 客户端会使用 Proxy 返回的 endpoint。若容器内监听 `8081`、宿主机仅映射为 `18082`，客户端可能在首次连接后改连宿主机不可达的 `127.0.0.1:8081`。

本项目将 Proxy 内部和宿主机 gRPC 端口统一为 `18082`：

- `rmq-proxy.json`: `grpcServerPort = 18082`
- Compose: `127.0.0.1:18082 -> 18082`
- Remoting Proxy: `127.0.0.1:18081 -> 8080`

该配置已由 RocketMQ Java Client 的真实发送、消费和确认测试验证。

在当前 Docker Desktop/WSL 后端下，宿主机端口转发不一定表现为普通
`Get-NetTCPConnection` 可见的长期 `LISTENING` 项。因此不能只凭宿主机监听列表
判定 RocketMQ Proxy 未启动或端口不可用。RocketMQ 启动门禁至少同时核对：

- `docker ps` / `docker inspect` 中容器运行状态和端口发布事实；
- NameServer、Broker、Proxy 的服务启动日志；
- 真实 RocketMQ Java Client 的发送、消费和确认结果。

宿主机监听列表可作为辅助诊断，但不能替代真实客户端门禁，也不能据此重写已经验证
正确的 `18082 -> 18082` gRPC 映射。

## 6. 项目冒烟脚本保护

`backend/run-foundation-smoke.ps1` 默认先调用本页诊断脚本。诊断失败、Docker 未就绪或任一必需容器未运行时立即退出，不自动启动、重启或重配中间件。只有已经完成等价人工检查时才使用 `-SkipNetworkPreflight`。

2026-07-22 已修复机器级 `D:\DevTools\Network\check-dev-network.ps1` 的旧六容器断言。脚本现在按名称验证 MySQL、Redis、Nacos、RocketMQ NameServer/Broker/Proxy 和 MinIO 共 7 个必需容器全部运行，不要求运行容器总数恰好为 7，因此按需观测或隔离实验容器不会造成误报。诊断失败时仍应先核对具体失败项，不得据此盲改网卡跃点、系统路由、Docker 数据或全局镜像源。

Redis 停机属于有状态故障注入，默认不执行。需要专门验证本地降级时显式运行：

```powershell
.\run-foundation-smoke.ps1 -EnableRedisFaultInjection
```

无该开关的常规真实冒烟不会停止 Redis；清理阶段只恢复由本次脚本明确停止的容器。

## 7. 当前机器级风险项

以下项目已在本轮采集，但目前只属于风险项，不能单独当作根因：

- Realtek PCIe GbE 驱动版本为 `1168.20.729.2024`；
- 网卡开启了 EEE、环保节能、Power Saving Mode 和 Gigabit Lite；
- 物理以太网绑定了 VMware Bridge Protocol，但项目约定 VMware 只使用
  VMnet8 NAT；
- 部分启动过程中出现 Hyper-V `FSE Switch` 旧端口恢复失败，随后临时虚拟
  端口被删除；
- 物理以太网当前未绑定 Hyper-V Extensible Virtual Switch。

如果后续做机器级治理，必须一次只改变一个变量，并记录可回退值：

1. 先核对联想针对 Legion Y7000P IRX9（机型 `83DG`）提供的 OEM 有线网卡
   驱动，不直接安装来源不明的通用驱动；
2. 确认不使用 VMware 桥接后，可单独验证关闭物理网卡上的
   VMware Bridge Protocol；
3. 再分别验证关闭 EEE/环保节能，不能一次关闭所有高级属性后宣称已经找到
   根因；
4. 不依据 `FSE Switch` 单条日志关闭 WSL mirrored networking、Hyper-V、
   `ms_l2bridge` 或 Windows 防火墙组件。

每一步都必须经过一次宿主机检查、一次重启复验和一次 Docker/WSL 恢复验证。
如果问题没有改善，应恢复原值，不继续叠加修改。

## 8. 高并发与动态端口边界

本机 IPv4/IPv6 的 TCP、UDP 动态端口范围均为 `49152-65535`，共 16384 个；
本轮采集时只有 `50000-50059` 为系统管理的排除范围。历史端口耗尽事件说明单机压测
不仅受 CPU、内存和 JVM 数量限制，也受宿主机连接生命周期限制。

### 8.1 2026-07-30 复核证据

Windows System 日志在以下时间记录了 TCP `4231`：

- 2026-07-23 16:53:48；
- 2026-07-24 00:59:17、14:14:08；
- 2026-07-25 22:43:11；
- 2026-07-27 09:52:18；
- 2026-07-28 03:06:10；
- 2026-07-30 06:18:50。

同一天还在 2026-07-30 08:14:55 记录了 UDP `4266`。该机器上的两条事件都由
`Tcpip` Provider 写入；`4266` 虽然描述 UDP 临时端口分配失败，但不能假定其
ProviderName 一定是 `Udpip`。

这说明动态端口失败不是单次猜测，而是在多轮真实链路、审查或高连接实验期间
反复出现的机器级容量现象。2026-07-30 事后采集时连接已经回落到约 592 个
`TIME_WAIT`、68 个 `ESTABLISHED` 和 58 个 `BOUND`，内存仍有约 7.5 GiB
可用；同时没有发现 Codex、Chrome、显示驱动或 Windows 资源耗尽检测器的对应
崩溃事件。因此只能确认上述历史时间点发生过“新建连接资源耗尽”，不能根据
左上角闪现的小窗口认定控制台宿主、Clash 或某个单一进程就是根因。

TCP `4231` 会使新的宿主机出站连接无法取得临时端口。Clash 即使仍监听
`127.0.0.1:7897`，其新上游连接也可能超时；依赖持续后端连接的 Codex 则可能
表现为请求超时、任务掉线或界面退出。这是机制上的可能后果，不是对每次 Codex
退出的归因。监听存在只能证明代理进程活着，不能证明代理链路还能建立新连接。

### 8.2 2026-07-30 Codex、代理超时与闪窗事件重建

本次故障不是一个根因解释全部现象，而是两个问题在同一工作窗口叠加：

1. **连接故障**：06:18:50 已出现 TCP `4231`，08:14:55 又出现 UDP
   `4266`。09:09–09:17 的 Codex 本地日志连续记录连接发送失败、TLS 握手
   `unexpected EOF`、模型刷新超时和等待辅助进程退出超时。用户观察到 Codex
   服务退出后代理短暂可用，约两分钟后所有代理/VPN 请求均超时；09:18:36 由用户
   正常发起重启。这个链条与动态端口耗尽后“监听仍在、但新连接无法建立”的机制
   一致。
2. **左上角闪窗**：重启后的现场进程快照直接抓到 `powershell.exe` 由
   `ChatGPT.exe` 启动，命令内容是在采集目标进程的 CPU、内存和存活时间；同时
   `pwsh.exe` 由 `codex.exe` 启动以执行本机检查，两者各自创建
   `conhost.exe`。多个 Codex 任务或连续本机命令会放大窗口闪现次数。机器级
   Redis 在该快照中是开机后同一个服务进程，没有反复重启，因此不是这轮连续
   闪窗的来源。

因此最终判别是：

- `PowerShell/pwsh + conhost` 未完全隐藏，解释左上角闪窗；
- TCP/UDP 动态端口耗尽，解释 Clash 仍监听但代理上游和 Codex 新连接逐步失败；
- 闪窗本身不会耗尽全部网络端口，也不能仅凭时间相邻就说它直接弄坏了网络；
- 多任务并行会同时增加辅助进程和连接压力，是风险放大器，但 Windows
  `4231/4266` 事件不记录占满端口的用户态 PID，重启后不能追溯并锁定唯一责任
  进程。

早先内置浏览器控制层访问 `ab.chatgpt.com` 的一次 10 秒超时发生时，近 15 分钟
没有新的 `4231/4266`，本地 Storefront 和 Mock API 仍成功。该证据继续成立：
单次代理或上游超时可以独立发生；只有本次新增的端口事件、持续连接错误和用户
时间线组合起来，才足以把后续全局超时归入动态端口耗尽。

### 8.3 现场取证

故障发生时应先停止继续升压，并在重启、禁用网卡或重载代理前采集：

```powershell
$connections = Get-NetTCPConnection -ErrorAction SilentlyContinue
$connections |
  Group-Object State |
  Sort-Object Count -Descending |
  Select-Object Count, Name

$connections |
  Where-Object OwningProcess -gt 0 |
  Group-Object OwningProcess |
  Sort-Object Count -Descending |
  Select-Object -First 30 Count, Name

Get-WinEvent -FilterHashtable @{
  LogName = 'System'
  Id = 4231, 4266
  StartTime = (Get-Date).AddMinutes(-30)
} |
  Select-Object TimeCreated, Id, ProviderName, Message

netsh int ipv4 show dynamicport tcp
netsh int ipv4 show excludedportrange protocol=tcp
```

事件 XML 中的 `Execution ProcessID=4` 是内核记录者，不是制造端口耗尽的用户态
进程。要定位责任进程，必须在故障仍发生时同时保存：

```powershell
Get-NetTCPConnection |
  Where-Object OwningProcess -gt 0 |
  Group-Object OwningProcess |
  Sort-Object Count -Descending |
  Select-Object -First 30 Count, Name

Get-NetUDPEndpoint |
  Group-Object OwningProcess |
  Sort-Object Count -Descending |
  Select-Object -First 30 Count, Name

Get-CimInstance Win32_Process |
  Select-Object ProcessId, ParentProcessId, Name, CreationDate, CommandLine
```

`TIME_WAIT` 连接通常已经不再归属于原始进程。若采集发生在端口回收之后，只能
确认历史上发生过耗尽，不能再用当前进程连接数反推责任进程。下一次需要把升压
阶段、连接状态、主要 PID、Clash 真实请求和事件时间统一记录，才可以定位是压测
连接未复用、WebSocket 重连、代理测速，还是多个任务叠加造成。

后续 1000 量级并发验证必须：

- 使用连接池和 HTTP keep-alive，避免为每个请求无界创建新连接；
- 分阶段升压并同时采集活动连接、`TIME_WAIT`、动态端口使用量和主要进程；
- 不让多组高连接实验、镜像拉取、代理测速和全量中间件验证同时运行；
- 前端全量门禁、真实浏览器人工复核和后端高并发实验串行执行，不同时拉起多套
  Chrome、Vite、Mock API、Java 服务或压测客户端；
- 同一台机器只运行一个重型 Codex 任务；出现连续闪窗时先停止新的工具调用，
  不要因为看到 `conhost` 就批量结束 Codex 的长期解析器或浏览器辅助进程；
- 出现 `4231` 或 `4266` 后停止升压，先定位连接来源；
- 不以增大动态端口范围或缩短 `TIME_WAIT` 代替连接复用和并发边界治理。

端口耗尽属于宿主机容量问题，不等于数据库、RocketMQ、Redis 或业务服务已经
失败。业务测试报告必须分别记录宿主机连接资源和服务端结果。

### 8.4 2026-08-02 UDP 事件与传输对照

2026-08-02 14:08:08，Windows System 日志再次记录 UDP `4266`
（`Tcpip`，RecordId `113434`）。事件发生后的第一阶段快照显示 Docker 引擎和
WSL 均未运行；14:12 的复测中，宿主机直连、当前全局代理、两个 VLESS 节点和
两个 Hysteria2 节点全部成功，动态 UDP 端口只有 14 个。

这组证据修正了此前过强的推断：

- `4266` 可以在 Docker、WSL、项目服务和 Clash TUN 均未运行时出现，不能把它
  自动归因于项目高并发、Docker 容器或 TUN 虚拟网卡；
- 事后只有十几个可见 UDP 动态端口，不支持“16384 个可见 UDP endpoint 持续
  占满”的说法；仍可能存在瞬时分配风暴、WFP/UDP 分配状态异常或大量不可见的
  短生命周期请求，但当前证据不能在这些机制中确定一个根因；
- 单条 `4266` 不等于网络已经整体故障。本次事件发生后，直连和代理请求仍然
  成功，因此必须把事件、请求失败和现场资源快照按时间关联后再判断。

同日 14:43:52，在 Docker、WSL、Java 和 Node 仍未运行时又记录了一条 TCP
`4231`（`Tcpip`，RecordId `113538`）。约两分半后的事后快照只有 371 条 TCP
连接，其中 `ESTABLISHED=120`、`TIME_WAIT=117`、`BOUND=90`，动态端口余量
门禁仍低于 2%。该事件发生在一次最小代理传输自测结束约一分钟后，但现场监控当时
已经停止，不能据此把事件归因于自测、Codex 或 Clash。它再次说明当前可见连接数
不能重建事件瞬间；下一次必须同时采集 TCP endpoint、状态和主要 PID。

为隔离 Docker 状态和代理传输类型，随后使用固定匿名节点、固定探测 URL、固定
次数和 500ms UDP 采样完成了四阶段对照：

| 阶段 | 环境与结果 | UDP 监控 |
| --- | --- | --- |
| A：Docker/WSL 关闭 | 直连和当前代理各 `3/3`；两个 VLESS 共 `6/6`；两个 Hysteria2 共 `6/6` | 事件后快照动态 UDP 端口约 14 |
| B：启动 Docker | Docker Desktop 自动恢复了 7 个核心容器，因此该阶段不能作为“只有引擎”的纯对照 | 150 秒、294 个样本；endpoint 峰值 32，动态端口峰值 13；无新 `4231/4266` |
| C：7 个核心容器运行 | 网络门禁全部通过；直连和当前代理各 `3/3`；两个 VLESS 共 `2/6`；两个 Hysteria2 共 `6/6` | 180 秒、353 个样本；endpoint 峰值 32，动态端口峰值 13；无新 `4231/4266` |
| D：关闭 Docker 后立即复测 | 直连和当前代理各 `3/3`；两个 VLESS 恢复为 `6/6`；两个 Hysteria2 保持 `6/6` | 90 秒、177 个样本；endpoint 峰值 32，动态端口峰值 13；无新 `4231/4266` |

当前只能确认：

> 这一次实验中，7 个容器运行期间两个特定 VLESS 节点发生退化，Docker 关闭后
> 立即恢复。

这是相关性，不是根因证明。阶段 B 自动恢复容器，缺少“只启动 Docker 引擎”的
纯对照；阶段 C 到 D 也只完成了一轮。若要提高因果可信度，必须在监控伴随下至少
再重复一次 C → D，并保持节点、URL、次数、启动顺序和其他负载不变。

两个 Hysteria2 节点在容器运行期间仍为 `6/6`，反而不支持“UDP/QUIC 节点优先
崩溃”或“VLESS UDP 隧道泄漏”的结论。当前 VLESS 节点的外层连接表现为 TCP；
Clash 配置中的 `udp: true` 只表示允许转发 UDP 流量，不能据此把外层传输认定为
UDP。后续必须分别记录代理节点类型、实际拨号协议和宿主机端口事件，不能按节点
名称猜测传输机制。

### 8.5 动态端口现场监控工具

`backend/tools/capture-udp-port-exhaustion.ps1` 以 500ms 为最小采样间隔采集
TCP connection 和 UDP endpoint，并以启动时最新事件 RecordId 为基线。Windows
网络 cmdlet 的执行时间也计入每轮耗时，因此实际频率可能低于 2 次/秒；该工具用于
保存事件后的完整现场，不能承诺还原毫秒级瞬时分配峰值。发现新的 `4231/4266`
时，它会立即保存：

- 事件时间、RecordId 和消息；
- TCP/UDP endpoint、动态端口数量、TCP 状态及主要 PID；
- 主要进程事实；
- 网络 compartment、网卡、HNS endpoint；
- 近 10 分钟 FSE Switch 警告。

最小自测：

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
pwsh -File .\backend\tools\capture-udp-port-exhaustion.ps1 `
  -DurationSeconds 5 `
  -OutputDirectory ".\backend\.run\udp-monitor-selftest-$stamp"
```

正式实验应先启动监控，再执行唯一一组受控场景；允许“监控 + 当前场景”并行，
但不允许同时运行多组中间件、代理测速、浏览器回归或压测：

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$monitorDirectory = ".\backend\.run\udp-monitor-$stamp"
pwsh -File .\backend\tools\capture-udp-port-exhaustion.ps1 `
  -DurationSeconds 300 `
  -OutputDirectory $monitorDirectory
```

`backend/tools/measure-local-proxy-transports.ps1` 通过 Mihomo 本地命名管道只读
测量固定数量的 VLESS/Hysteria2 候选节点，同时检查宿主机直连和当前全局代理。
它不会切换全局代理，输出只保存节点名称的 SHA-256 短标识，不保存订阅密钥：

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
pwsh -File .\backend\tools\measure-local-proxy-transports.ps1 `
  -Phase 'controlled-probe' `
  -OutputPath ".\backend\.run\proxy-transport-$stamp.json" `
  -CandidatesPerTransport 2 `
  -AttemptsPerCandidate 3
```

监控输出目录必须是不存在或为空的新目录。若产生 `incident.json`，应停止继续
升压并先分析现场，不要先重启 Clash、Docker、网卡或整机。传输测量只能用于
比较同一实验窗口的退化差异，不能用一次成功率变化直接宣布 Docker、FSE、
Realtek 驱动或某种代理协议是根因。

## 9. 安全恢复顺序

发生“网络断开”时按以下顺序处理：

1. 先执行宿主机诊断并保留失败项；
2. 宿主机直连正常、代理失败时，处理 Clash 节点或核心；
3. 宿主机正常、WSL/Docker 失败时，执行既有 WSL/Docker 重载脚本；
4. 只有宿主机网关、DNS 或直连失败时，才人工禁用并启用物理以太网；
5. 网络恢复后重新运行宿主机诊断，再启动 Docker 和项目中间件；
6. 仍然失败时保留现场，不修改网卡跃点、默认路由、Docker 数据、全局代理或
   镜像源，先分析对应故障层。

网络恢复动作必须由人明确触发。项目测试脚本只能失败关闭并报告证据，不能为了
继续测试自动重启代理、网卡、Docker、路由器或有状态中间件。

## 10. 开机启动项审查

本轮对注册表启动项、启动文件夹、登录/启动计划任务、自动服务和进程父子关系
进行了只读审查，结论如下：

- Clash Verge 配置为 `enable_auto_launch: false`，Clash Verge Service 也处于
  Disabled；普通注册表启动项、启动文件夹和计划任务中均没有 Clash；
- 本轮 Clash 是登录后人工启动，父进程为 Explorer，不属于自动服务抢占；
- Clash 在系统重启前完成了系统代理重置、核心停止和 DNS 清理，未留下
  `127.0.0.1:7897` 的失效系统代理；
- Windows NCSI 在 Clash 启动前已经把物理以太网判定为 IPv4 Internet；
- Clash 启动后，日志出现代理上游节点连接超时和 DNS deadline；相同上游超时
  在重启前也已经存在；
- Hyper-V `FSE Switch` 恢复错误出现在启动早期，但随后 NCSI 仍成功判定
  IPv4 Internet，因此该错误目前只能作为虚拟网络状态不干净的背景证据；
- WSLService、HNS、宿主机 MySQL、RabbitMQ、Redis、华为和联想后台服务会在
  开机窗口启动，但当前没有证据显示它们在该窗口制造了动态端口耗尽或大量
  外部连接；
- WSearch 和抖音客户端性能优化服务存在启动错误或超时，但与本轮代理失败没有
  已证明的因果关系。

因此，本轮最强证据指向代理节点或代理出站路径不稳定，而不是“启动项太多导致
网卡坏死”。禁用再启用物理网卡可能触发了 NCSI、WinINet 和 Clash 的重新探测，
也可能只是与上游节点恢复时间重合。

以后重启后的正确判别顺序是先不要启动 Clash：

```powershell
curl.exe --noproxy '*' -I --connect-timeout 10 https://repo.maven.apache.org/maven2/
```

宿主机直连成功后再启动 Clash，并检查真实代理请求：

```powershell
curl.exe -x http://127.0.0.1:7897 -I --connect-timeout 10 https://repo.maven.apache.org/maven2/
```

- 直连成功、代理失败：切换 Clash 健康节点或重载核心；
- 直连和代理都失败：再检查网关、DNS、网卡和路由器；
- 两者都成功、只有容器失败：处理 WSL/Docker；
- 只有出现 TCP `4231` 或 UDP `4266` 时，才进入动态端口耗尽排查。

现阶段没有依据批量禁用开机服务，也没有依据修改网卡跃点、WSL mirrored
networking、Hyper-V 或 Realtek 全部高级属性。
