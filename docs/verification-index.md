# 验证索引

PlainJournal 把运行路径分为三档。每一档证明的对象不同，不能用轻量演示替代真实
基础设施，也不要求普通使用者先跑完整实验室。

## 1. UI Demo

**用途**：查看顾客端、管理端、主题、响应式布局和主要交互。

**资源**：Node.js、pnpm、Chromium；不启动 Docker 和 Java 服务。

```bash
cd frontend
corepack enable
pnpm install --frozen-lockfile
pnpm demo:start
```

- 顾客端：`http://127.0.0.1:18300`
- 管理端：`http://127.0.0.1:18301`

完成后运行 `pnpm demo:stop`。该层使用固定 Mock API，只证明前端交付，不证明数据库、
消息队列或跨服务一致性。

## 2. Core Smoke

**用途**：从真实核心中间件验证身份、商品、库存、营销、下单、支付、履约、取消、
结果未知恢复、权限和清理。

**资源**：PowerShell 7、JDK 17、Docker、核心中间件和代表业务服务。

```powershell
cd backend
./tools/verify-core-smoke.ps1
```

完整资源、时间、宿主机预检和清理边界见 [Core Smoke 指南](core-smoke.md)。该层是普通
评审者最值得运行的真实路径。

## 3. Full Lab

**用途**：验证代表服务三实例、滚动升级、消费者竞争、1000/100 容量、故障注入、
分片/副本/归档、Chat、ClamAV、OpenSearch、观测追踪和对账恢复。

主要入口：

- `backend/verify-trade-outbox-multi-instance.ps1`
- `backend/verify-trade-container-multi-instance.ps1`
- `backend/verify-trade-consumer-multi-instance.ps1`
- `backend/verify-gateway-rolling-upgrade.ps1`
- `backend/verify-trade-dual-version-compatibility.ps1`
- `backend/tools/verify-m8-chat-frontend-workspace.ps1`
- `backend/tools/verify-m8-notification-delivery.ps1`
- `backend/tools/verify-m8-catalog-search.ps1`
- `backend/tools/verify-m8-analytics.ps1`

Full Lab 必须按文档 Profile 串行运行，先执行仓库内的
`backend/tools/check-verification-host.ps1`，再确认场景要求的 Docker Profile。预检
检查内存、动态端口、近期端口耗尽事件和 Docker 状态，不绑定具体代理或网卡。它不是
GitHub Actions 的常驻任务。单变量、组合批次、带种子的随机顺序、
资源停止线和并发数字的统一解释见
[参考基线与 Pro 边界](reference-baseline-and-pro-boundary.md)。

在业务组合前，可用以下入口逐个验证核心中间件启动、基本读写/连接、资源占用和清理：

```powershell
cd backend
./tools/verify-middleware-isolation.ps1
```

RocketMQ 的 NameServer、Broker 和 Proxy 是一个不可拆分阶段；该入口不是业务并发测试，
不能替代 Core Smoke、故障注入或最终一致性核对。

七个核心容器已健康且宿主机预检通过后，容量阶梯使用：

```powershell
cd backend
./tools/verify-foundation-capacity-ladder.ps1
```

该入口逐级运行 `1 -> 10 -> 50 -> 100 -> 300 -> 500 -> 1000`，每级都复用完整数据库
与跨服务一致性断言，并在首个资源或正确性失败处停止。脚本存在不代表最高一级已经通过；
公开摘要只记录实际完成且工作区干净的证据。

## 公共 CI

GitHub Actions 公开运行：

- 后端 `clean verify` 与 PMD；
- 前端分层、类型、单元/契约、开发态 E2E、生产构建 E2E 和构建；
- 后端架构边界、Markdown 链接、Compose 解析和生成文档漂移；
- CodeQL、Dependency Review、pnpm audit、Dependabot；
- 标签 Release 的 ZIP、SHA-256、清单和 SPDX SBOM。

当前数字见[验证摘要](verification-summary.md)，终局真实证据见
[验收证据](evidence/README.md)，当前项目边界由
[参考基线与 Pro 边界](reference-baseline-and-pro-boundary.md)统一说明。逐批实验记录
通过 Git 历史追溯，不再作为主分支文档目录。
