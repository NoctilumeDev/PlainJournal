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

最初的超时不是已证实的“多默认路由争抢”。采集结果显示网段没有重叠、默认路由只有一条。问题集中在代理链路同步和代理服务瞬时不可用：诊断曾发现 Clash 端口仍监听但请求无响应；重载 WSL/Docker 后，宿主机直连、宿主机代理和容器外网访问均恢复。

因此应把 VMware、Docker 和 Clash 同时运行视为触发条件，而不是把三个软件本身判定为争抢网卡的根因。

## 4. 启动与诊断

建议启动顺序：

1. 启动 Clash Verge，确认 `127.0.0.1:7897` 可实际代理请求。
2. 启动 Docker Desktop，等待 `docker info` 成功。
3. 需要虚拟机时再启动 VMware，并保持 VMnet8 NAT。

诊断命令：

```powershell
& 'D:\DevTools\Network\check-dev-network.ps1'
```

重载 WSL 和 Docker 网络：

```powershell
& 'D:\DevTools\Network\restart-wsl-docker.ps1'
```

脚本及机器级说明保存在 `D:/DevTools/Network`。该方案没有删除网卡、修改系统路由或迁移 Docker 数据。

## 5. RocketMQ 端口经验

RocketMQ 5.x gRPC 客户端会使用 Proxy 返回的 endpoint。若容器内监听 `8081`、宿主机仅映射为 `18082`，客户端可能在首次连接后改连宿主机不可达的 `127.0.0.1:8081`。

本项目将 Proxy 内部和宿主机 gRPC 端口统一为 `18082`：

- `rmq-proxy.json`: `grpcServerPort = 18082`
- Compose: `127.0.0.1:18082 -> 18082`
- Remoting Proxy: `127.0.0.1:18081 -> 8080`

该配置已由 RocketMQ Java Client 的真实发送、消费和确认测试验证。
