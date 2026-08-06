# 本地开发网络与 Windows 故障边界

本说明只保留可移植的诊断与测试规则。2026-07-30 至 2026-08-02 的机器级事件、窗口
闪烁、代理超时和 Windows 事件日志原始分析由 Git 历史保存，不再携带本机路径和
一次性诊断流水。

## 1. 适用范围

PlainJournal 的 Full Lab 会同时使用多个 Java 进程、Docker 端口、浏览器连接、
RocketMQ 长轮询和本地代理。Windows 单机出现连接失败时，必须先区分：

1. 业务服务或中间件故障；
2. 本地代理上游节点故障；
3. Windows 动态端口或网络栈容量问题；
4. Docker、WSL、VPN/代理的路由或 MTU 冲突；
5. Codex/PowerShell 可见控制台窗口问题。

这些现象可能同时出现，但不能因为时间接近就认定为同一根因。

## 2. 项目网络原则

- 业务服务和中间件只绑定文档声明的本地端口；
- 容器、JVM、浏览器和压测工具必须按批次启动和清理；
- Core Smoke 与 Full Lab 不依赖某个仓库外的私有诊断脚本；
- 本地代理可以存在，但项目测试不得修改系统路由、网卡跃点、防火墙或代理配置；
- 网络异常时先保存现场，再停止升压和新进程创建；
- 需要比较 Docker 网桥、Host 网络或直连时，一次只改变一个变量。

项目端口与服务入口见[服务架构](02-service-architecture.md)和
[Docker 说明](../deploy/docker/README.md)。

## 3. 启动前检查

在 PowerShell 7 中执行：

```powershell
Get-CimInstance Win32_OperatingSystem |
  Select-Object FreePhysicalMemory, TotalVisibleMemorySize

Get-NetTCPConnection |
  Group-Object State |
  Sort-Object Count -Descending |
  Select-Object Name, Count

netsh int ipv4 show dynamicport tcp
netsh int ipv4 show dynamicport udp

docker info
docker compose -f deploy/docker/compose.yaml config --quiet
```

继续检查项目端口是否被其他进程占用：

```powershell
$ports = 18100..18110
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object LocalPort -in $ports |
  Sort-Object LocalPort |
  Select-Object LocalAddress, LocalPort, OwningProcess
```

若已有 PlainJournal 服务或容器在运行，应先确认它们属于本轮测试；不要让上一批残留
混入下一批。

## 4. 本地代理检查

`Test-NetConnection` 是 PowerShell cmdlet，不能直接在 `cmd.exe` 中运行：

```powershell
Test-NetConnection 127.0.0.1 -Port 7897
Get-NetTCPConnection -LocalPort 7897 -ErrorAction SilentlyContinue
```

使用 curl 时必须写正确的环回地址和实际代理端口：

```powershell
curl.exe -x http://127.0.0.1:7897 --connect-timeout 6 https://httpbin.org/ip
```

`127.0.0.0` 不是这里应使用的代理地址，`789` 也不能代替实际监听的 `7897`。

判断方式：

- 本地端口未监听：代理进程或配置问题；
- 本地端口监听，但所有上游都超时：检查节点、DNS、Windows 网络容量和路由；
- 国内直连正常、单个代理节点失败：优先判断为上游节点或线路问题；
- 只有项目服务失败：检查服务日志、Nacos 路由、中间件和端口，不先改系统网络。

代理日志中的 `operation was canceled` 只能证明该次上游连接被取消，不能单独证明
防火墙、Docker 或项目代码是根因。

## 5. Windows 动态端口

Windows 事件 `4231`（TCP）或 `4266`（UDP）表示系统在对应协议上无法分配临时端口。
此时代理进程可能仍监听本地端口，但无法建立新的上游连接，因此表现为“代理还在，
所有新请求却超时”。

现场检查：

```powershell
netsh int ipv4 show dynamicport tcp
netsh int ipv4 show dynamicport udp

(Get-NetTCPConnection |
  Where-Object State -in 'Established', 'TimeWait', 'SynSent').Count

Get-NetTCPConnection |
  Group-Object State |
  Sort-Object Count -Descending |
  Select-Object Name, Count
```

还应查看 Windows 事件查看器中的 System 日志，记录事件时间、协议和进程现场。正常
时刻连接数较低不能否定此前已经发生过端口耗尽；重启会清除大部分现场。

不要在没有复现证据时直接扩大动态端口范围、修改 TCP 全局参数或关闭防火墙。先找出
制造大量短连接、`TIME_WAIT`、失败重连或 UDP 会话的进程。

## 6. Docker、VPN 与代理

以下是需要用控制变量验证的候选原因，不是默认结论：

- Docker 网桥 NAT 增加连接跟踪和端口占用；
- VPN 隧道 MTU 小于容器网桥 MTU，导致分片和重传；
- Docker 子网与 VPN 使用的 `10.x` 或 `172.x` 网段冲突；
- 容器把 `127.0.0.1` 当作宿主机代理地址；
- 容器、宿主机代理和 VPN 形成重复转发。

检查：

```powershell
docker network inspect bridge
Get-NetRoute | Sort-Object DestinationPrefix, RouteMetric
Get-NetIPInterface | Sort-Object InterfaceMetric
```

容器内的 `127.0.0.1` 指向容器本身。确需访问宿主机代理时使用经过验证的
`host.docker.internal` 或明确宿主机地址，并记录这一变化。

只有在 Linux Docker/WSL 环境中实际观察到 conntrack 指标时，才把
`nf_conntrack_count`、`nf_conntrack_max` 写入结论；不能把 Linux 诊断直接套到
Windows 宿主机。

## 7. 压测规则

压测必须复用连接并按阶梯升压：

```text
1 -> 10 -> 50 -> 100 -> 300 -> 500 -> 1000
```

当前公开基线仍是 1000 个请求、100 并发。更高同时在途请求数只有在脚本、资源和业务
结果均保存后才能写入结论。

每一级检查：

- 错误率、P95/P99 和连接状态；
- JVM、Docker、代理和宿主机内存；
- 库存、订单、支付、退款和 Outbox 一致性；
- MQ 积压、失败重试和最终收敛；
- 动态端口、`TIME_WAIT` 和异常重连；
- 测试结束后的进程、端口、容器与数据清理。

达到资源停止线、代理异常或 Windows 网络事件时立即停止升压。完整方法见
[参考基线与 Pro 边界](reference-baseline-and-pro-boundary.md)。

## 8. 恢复顺序

1. 停止新的压测、浏览器自动化和并行任务；
2. 保存代理日志、服务日志、连接状态和 Windows 事件；
3. 串行停止本轮启动的前端、JVM 和容器；
4. 确认项目端口和进程已释放；
5. 单独验证本地代理端口与一个稳定上游；
6. 单独验证 Docker Engine 和核心中间件；
7. 从最小 Core Smoke 重新建立基线；
8. 只有最小链路稳定后，才逐批恢复 Full Lab。

重启电脑可以恢复网络，但会丢失现场，只应作为保存证据后的最后恢复手段。

## 9. 控制台闪窗

左上角短暂出现的 PowerShell/conhost 窗口，通常来自桌面端工具启动可见命令宿主。
它与动态端口耗尽是两个问题：多任务会同时放大窗口数量和网络连接压力，但闪窗本身
不等于代理故障。

本项目的本地脚本启动后台辅助进程时应使用隐藏窗口；测试期间仍建议保持单任务和
串行中间件批次，避免把工具进程噪声与业务故障混在一起。
