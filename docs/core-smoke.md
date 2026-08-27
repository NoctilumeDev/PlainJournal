# Core Smoke 冷启动指南

Core Smoke 复用 `backend/run-foundation-smoke.ps1`，不复制第二套业务脚本。包装入口
只负责前置条件、默认参数和清晰的失败提示。

## 证明范围

- 真实 MySQL、Redis、Nacos、RocketMQ 和 MinIO 连接；
- Gateway、Identity、Catalog、Inventory、Trade、Payment、Fulfillment、Marketing；
- 注册登录、角色边界、商品、库存预占、营销锁价、下单、支付、履约与取消；
- 幂等键、结果未知恢复、Outbox 收敛、所有者域对账；
- 结束后的 run-scoped 数据、端口和 JVM 清理。

该入口不默认运行观测、容量、Redis 故障、分片、Chat、ClamAV 和 OpenSearch。它们
属于 Full Lab。

## 环境

- Windows 11 或支持 PowerShell 7 与 Docker Desktop 的 Windows 环境；
- JDK 17；
- Docker Engine / Compose；
- 建议为 Docker 与 Java 进程预留至少 8 GiB 可用内存；
- 首次拉取镜像和 Maven 依赖不计入执行时间，稳定环境通常需要约 20–45 分钟。

复制 `deploy/docker/.env.example` 为忽略的 `.env`，在第一次启动 Compose 前先运行：

```powershell
cd deploy/docker
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
./bootstrap-resources.ps1 -PrepareEnvironmentOnly
docker compose --env-file .env --profile core up -d
./bootstrap-resources.ps1
```

准备阶段会在本地生成并严格校验 Nacos 签名密钥；非法或重复的自定义值会在容器启动前
被拒绝，不会被静默替换。已有 `.env` 不得用示例文件覆盖。不要把真实 `.env`、生成密钥、
原始 Compose 配置输出、Nacos 启动日志或中间件数据目录提交到 Git。

## 执行

```powershell
cd backend
./tools/verify-core-smoke.ps1
```

宿主机容量和 Docker 前置条件已由人工等价核对时，可以保留兼容参数并显式使用：

```powershell
./tools/verify-core-smoke.ps1 -SkipNetworkPreflight
```

参数名称为兼容旧脚本保留。它只跳过仓库内的宿主机预检，不跳过端口占用检查、业务
断言、数据库核对或最终清理，也不代表脚本会修改代理、网卡或系统网络配置。

## 失败处理

脚本失败时先保留当前终端输出，检查 `backend/.run/` 中对应运行目录，再确认：

1. 端口是否被非本次运行进程占用；
2. Docker Profile 和中间件健康状态；
3. `.env` 是否为当前仓库生成；
4. 代理、虚拟网卡和短连接压力是否影响镜像或依赖下载；
5. 最终清理是否报告未释放 JVM、端口、Topic、消费组或测试数据。

不要通过强杀未知端口监听者让测试“通过”。清理只能处理本次脚本拥有的资源。
