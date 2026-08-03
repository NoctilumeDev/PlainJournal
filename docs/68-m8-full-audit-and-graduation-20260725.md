# M8 全面审查、回归与毕业收口

> 审查日期：2026-07-25  
> 状态：已完成；M8.1–M8.12 当前代码、自动化、真实中间件与仓库质量门禁收敛  
> 下一阶段边界：本报告是 2026-07-25 历史快照；M9 仍冻结。当前工程结论以 2026-07-28 的 [M0–M8 三层证据审查](69-m0-m8-pre-m9-three-layer-audit-20260728.md)为准

## 1. 审查原则

- 唯一项目根目录为 `C:\Users\lenovo\Desktop\PlainJournal`。
- 当前大量未提交修改和新增文件均是用户成果；本轮没有执行 `reset`、`checkout`、
  `clean` 工作树或删除历史验证证据。
- 真实实验继续串行执行，遵守
  [本地开发网络基线](07-local-development-network.md)，不修改网卡、路由、代理、
  Docker 数据或全局镜像源。
- 自动化、H2 和浏览器夹具不能替代 MySQL、Redis、Nacos、RocketMQ、MinIO、
  ClamAV、SMTP、MySQL Spatial、OpenSearch 和故障恢复证据。
- M8 毕业只说明单机缩比下的机制、契约和恢复证据成立，不外推为生产 SLO、
  多机容灾或无限水平扩展。

## 2. M8 十二个切片

| 批次 | 能力 | 代表真实证据 |
| --- | --- | --- |
| M8.1 | Chat MySQL 可靠持久化、同事务 Outbox、客户端幂等 | `backend/.run/m8-chat-persistence-m8chat20260723144605/verification.json` |
| M8.2 | Redis 在线路由、双 Chat 节点定向投递、离线回放 | `backend/.run/m8-chat-realtime-m8rt20260723160906/verification.json` |
| M8.3 | 私有 MinIO 附件、完整性、授权下载和孤儿清理 | `backend/.run/m8-chat-attachments-m8attd42436c3d59b/verification.json` |
| M8.4 | 浏览器短期、单次 WebSocket 握手票据 | `backend/.run/m8-chat-browser-ticket-m8wse75d2b880a0a/verification.json` |
| M8.5 | Chat 消费失败台账、MySQL 租约恢复和观测 | `backend/.run/m8-chat-consumer-failures-m8cf20260725024042/verification.json` |
| M8.6 | 顾客/客服文本工作区与结果未知恢复 | `backend/.run/m8-chat-frontend-final-20260724/verification.json` |
| M8.7 | 附件隔离、ClamAV 扫描、有限重试和审计重扫 | `backend/.run/m8-chat-malware-20260724-184718/verification.json` |
| M8.8 | Notification 站内信、邮件租约投递和审计恢复 | `backend/.run/m8-notification-20260724-195313/verification.json` |
| M8.9 | Fulfillment MySQL 空间事实与可重建 Redis GEO | `backend/.run/m8-fulfillment-geo-20260724-205748/verification.json` |
| M8.10 | 商品评价资格、并发幂等、回复和审核 | `backend/.run/m8-product-reviews-20260725001057fdfc7e/verification.json` |
| M8.11 | OpenSearch 投影、降级、蓝绿重建和对账 | `backend/.run/m8-catalog-search-20260724055739e5c718/verification.json` |
| M8.12 | Analytics 来源事件、运营投影、对账和审计重建 | `backend/.run/m8-analytics-202607248c8eb758/verification.json` |

上述十二份权威证据文件均已在 2026-07-25 反查存在。各专题文档继续保存完整命令、
状态机、失败过程、清理边界和不宣称内容。

## 3. 整体审查发现与修复

### 3.1 Chat 消费失败恢复所有权

M8.5 初版把临时失败保存为 `RETRYING` 后不 ACK，依赖 RocketMQ POP revive
再次投递。整体审查没有沿用旧成功日志，而是用新运行级消费组重新故障注入：

```text
backend/.run/m8-chat-consumer-failures-m8cf20260725012254
backend/.run/m8-chat-consumer-failures-m8cf20260725013638
```

两次在 Redis 恢复后等待 360 秒仍未收敛。第二次已经预创建消费组并确认最新位点
模式，否定了“消费组自动创建竞争”假设。Broker 统计为：

```text
GROUP_GET_NUMS = 7
GROUP_CK_NUMS  = 8
GROUP_ACK_NUMS = 7
```

不可见时间调整到达 Broker，旧 receipt 已确认，但没有 retry topic、revive 或第二次
GET。最终机制改为：

```text
消费失败
  -> MySQL 保存 RETRYING、原始载荷、next_attempt_at
  -> 持久化成功后 ACK 原消息
  -> 到期记录由当前 Chat 实例条件抢占租约
  -> 领域动作成功后 RECOVERED
  -> 临时失败有限重排
  -> 契约错误或预算耗尽后 NEEDS_ATTENTION
```

`message_id + consumer_group` 唯一键、状态条件、`claim_owner` 和 `claim_until`
共同提供幂等与 owner 围栏。台账写入失败时不 ACK；租约丢失时旧 owner 不能覆盖
新 owner。真实 Redis 停机/恢复最终得到：

- 初始 `RETRYING`，Broker 投递次数 1；
- 原消息在失败事实提交后 ACK；
- MySQL 作业一次失败后 `attempts = 2`；
- Redis 恢复后 `RECOVERED`；
- `recoveryOwner = mysql-lease-retry`；
- 不要求 Broker 再投；
- MySQL、运行级消费组、18108 端口和验证 JVM 残留均为 0。

10:06 的成功运行读取到 09:37:31 的历史毒消息，10:41 的最终运行又读取到
10:06:48 的历史毒消息，证明 Topic 保留期内的旧消息可能污染全局计数。因此脚本
只用本次 payload marker、业务 `messageId` 和运行级消费组做核心断言，Actuator
总量只作为辅助观测。

### 3.2 前端供应链

使用项目默认 npmmirror 执行 `pnpm audit` 时，镜像缺少 audit API；改用命令行临时
指定官方 npm registry 后，发现测试工具链：

```text
@vue/test-utils
  -> js-beautify
  -> minimatch
  -> brace-expansion 2.1.2
```

命中高危拒绝服务公告。仓库级 pnpm override 已将 `brace-expansion` 固定为
`5.0.8`，锁文件和本地依赖同步更新。再次使用官方 audit API 返回已知漏洞 0，
随后完整 `pnpm check` 通过。

### 3.3 依赖、死代码和重复

- `mvn dependency:analyze` 的 Spring Boot Starter、自动配置、运行时驱动、
  Flyway、Nacos、Actuator、Tracing 和测试聚合项属于框架装配误报，不能机械删除；
- Chat 未使用分页或 SQL 拦截器，单独声明的 `mybatis-plus-jsqlparser` 已删除，
  Chat 冷测试随后通过；
- 排除 `.git`、`target`、`node_modules`、`dist`、`.run`、`test-results` 和
  Playwright 报告后审查 1,110 个仓库文件：空文件 0、被 Git 跟踪的生成物 0；
- Java、TypeScript、Vue、MJS 和 PowerShell 主体中独立
  `TODO/FIXME/HACK/XXX`、`System.out`、`System.err`、`printStackTrace` 命中 0；
- 对 997 个源码、脚本、配置和文档做精确 SHA-256 重复扫描，只剩 5 组小型边界
  文件：两端 `env.d.ts`、Vite 配置、Pinia 入口、Catalog API 适配器和多个服务的
  测试启动配置。它们属于独立应用或测试装配边界，当前抽取会增加耦合，不删除。

### 3.4 Chat 失败状态单调性与租约守卫

最终独立复核发现，Broker 晚到重复消息如果再次进入失败路径，旧更新条件可能把
已经 `RECOVERED` 的记录重新降级为 `RETRYING`，或者清除另一个实例当前持有的有效
MySQL 重试租约。修正后的失败记录更新只允许作用于没有有效租约的 `RETRYING`
记录，并保证：

- `RECOVERED` 与 `NEEDS_ATTENTION` 不回退；
- `attempts` 单调不减；
- `next_attempt_at` 不被晚到重复消息推迟；
- 有效 `claim_owner + claim_until` 不被 Broker 失败路径清除；
- 状态没有实际变化时不重复增加失败观测计数。

新增三条集成测试分别覆盖恢复后重复投递、有效租约保护和重试预算/调度时间单调性。
定向测试 6/6 通过后，又重新执行了全后端冷构建和静态门禁。

### 3.5 进入 M9 前的真实浏览器追加复审

追加复审没有只看应用端口和日志，而是同时使用人工浏览器与自动化 Chrome DevTools
Protocol 取证：

- 顾客登录、建立会话并发送消息；
- 管理端进入未认领队列，确认认领前正文不可见；
- 认领后读取并回复，顾客端无需刷新收到回复；
- 自动化再次注入创建响应丢失和发送响应丢失，均复用原幂等键并从 MySQL 权威事实恢复；
- 页面刷新后恢复历史，页面错误、控制台错误、HTTP 错误和非预期网络失败均为 0；
- 抓取 22 个授权 Chat 请求和 7 个 WebSocket 101 握手；
- JSON 和日志证据中密码、Bearer、票据与消息正文命中均为 0；
- 最终 Chat 行、Identity 用户、消费失败、Redis 键、RocketMQ 消费组、端口、JVM
  和 Vite 进程残留均为 0。

自动化证据位于：

```text
backend/.run/m0-m8-browser-control-final-20260725-r1/evidence/verification.json
```

人工浏览器还发现旧刷新令牌失效后登录页会展示后端英文错误。会话清理本身正确，
但该 401 属于预期本地会话失效，不应作为新的登录失败提示。顾客端和管理端现均在
清理旧令牌后静默进入登录页，并各新增一条回归用例。

## 4. 最终自动化与静态门禁

### 4.1 后端

最终执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn clean verify
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
mvn com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs `
  '-Dspotbugs.effort=Max' `
  '-Dspotbugs.threshold=Low' `
  -DskipTests
```

全量结果：

```text
14 个 Reactor 模块
97 份 Surefire 报告
399 tests
0 failures
0 errors
0 skipped
```

主要模块：

| 模块 | 测试 |
| --- | ---: |
| platform-common | 19 |
| Gateway | 11 |
| Identity | 8 |
| Catalog | 43 |
| Inventory | 31 |
| Trade | 113 |
| Payment | 46 |
| Fulfillment | 28 |
| Marketing | 24 |
| Chat | 59 |
| Notification | 11 |
| Analytics | 6 |

全 Reactor PMD 为 0 违规。全 Reactor SpotBugs 低阈值扫描共 308 条分类诊断：
Priority 1 为 0、Priority 2 为 242、Priority 3 为 66。该数字是静态分析分类基线，
不能写成“308 个已修复缺陷”；本轮阻断门槛仍是 Priority 1。

全量 Maven 日志保存在：

```text
backend/.run/m0-m8-final-backend-gate-20260725-browser-r1/maven.out.log
backend/.run/m0-m8-final-pmd-20260725-browser-r1/pmd.out.log
backend/.run/m0-m8-final-spotbugs-20260725-browser-r1/spotbugs.out.log
```

### 4.2 前端

运行时：

```text
Node.js 24.14.0
pnpm 11.9.0
```

完整 `pnpm check`：

- Foundation 38 tests；
- Storefront 53 tests；
- Admin 12 tests；
- 合计 103 个 Vitest；
- 7 个 Playwright E2E；
- 两端类型检查、生产构建和 axe 关键页面可访问性检查通过。

最终日志：

```text
backend/.run/m0-m8-final-frontend-gate-20260725-browser-r1/pnpm.out.log
```

### 4.3 脚本、文档、Compose 和 Git

- 46 个 PowerShell 脚本，Parser 错误 0；
- 76 个 Markdown，相对链接断链 0；
- Compose 基础配置及 8 个有效 Profile 组合全部可展开：
  `core`、`core+m3-gateway`、`core+m3-trade`、`core+m7-catalog-replica`、
  `core+m7-trade-sharding`、`core+m8-malware-scan`、`core+m8-search`、
  `core+observability`；
- `git diff --check` 通过，仅保留 Windows 工作树既有 LF/CRLF 提示；
- 没有构建产物被 Git 跟踪。

## 5. 资源与网络终态

最终真实故障脚本结束后独立反查：

- MySQL 本次失败台账残留 0；
- 两个运行级 RocketMQ 消费组残留 0；
- Chat 验证 JVM 0；
- 18108 监听 0；
- Redis 恢复为 `healthy`；
- MySQL、Redis、Nacos、RocketMQ NameServer/Broker/Proxy、MinIO 共 7 个核心容器
  全部运行；
- 网络门禁确认单一物理默认路由、Maven Central 直连、容器外网、Docker 和 7 个
  核心中间件均正常；本次唯一失败项是非必需的“Maven Central 强制经 Clash”
  探测。保留失败日志后，真实 Chat 复验显式跳过重复预检，没有修改代理、路由或
  网卡配置。

最终复审随后对 Broker 元数据做了比 `getConsumerConfig` 更深的一次反查。在先导
验证确认精确删除语义后，批量审计快照仍发现历史 M3/M6/M8/兼容性验证留下
36 个离线验证 offset 组和 25 个临时或 `%RETRY%` Topic。它们没有活动消费者、
TPS 或积压，不影响业务正确性，但证明原“零消费组残留”门禁只检查订阅组配置，
不足以覆盖 RocketMQ 仍保留 offset、重试 Topic 而订阅组配置已经不存在的状态。

本轮已：

- 只删除带明确 M3/M6/M8/POC 运行标识的历史验证资源；
- 保留 Trade、Inventory、Marketing、Payment、Fulfillment、Chat、Catalog 和
  Analytics 的稳定业务 Topic 与 offset；
- 修正 M3 Outbox/容器/消费多实例、M6 队列，以及 M8 Chat、Notification、
  Reviews、Analytics 验证脚本，使其同时检查订阅组配置、Broker offset 和
  `%RETRY%/%DLQ%` Topic；
- 让 M6 清理失败成为脚本失败，不再只写日志后继续报告成功；
- 最终反查得到临时 Topic 0、临时 offset key 0，`consumerProgress` 只剩正式
  业务组和 RocketMQ 系统组，7 个核心容器仍全部运行。

未修改网卡跃点、系统路由、Clash、Docker 数据、WSL 配置或全局镜像源。

## 6. M8 毕业结论

M8 当前已完成：

- 可靠 Chat、跨节点实时路由、浏览器认证、附件安全和客服工作区；
- Notification 可靠站内信与邮件派发；
- Fulfillment 空间物流事实与缓存重建；
- Catalog 商品评价与可重建搜索；
- Analytics 独立事件读模型、对账和审计重建；
- 消费失败、租约抢占、有限重试、人工关注、观测和最终清理；
- 后端、前端、依赖、供应链、脚本、Compose、文档和工作树质量门禁。

本节的 97 份报告、399 tests、308 条 SpotBugs 分类和 103 个 Vitest 都是
2026-07-25 的 M8 收口快照。2026-07-28 当前基线已经提升为 100 份 Surefire
报告、435 tests、313 条 SpotBugs 分类（P1=0）和 106 个 Vitest + 7 个 E2E，
并补齐全域三层证据矩阵。

没有发现阻断 M8 关闭的 P0/P1 缺陷，M8 毕业。工程复审完成只允许用户开始复审，
不等于 M9 准入；M9 必须继续冻结，等待用户单独确认。

## 7. M9 候选边界（尚未进入）

下一阶段按用户确定的单机边界实施：

- 商户固定为 3 个，用于练习商户身份、数据所有权、路由、越权隔离和结算边界；
- Go 服务只承担有明确来源事件和重建路径的异构统计读模型；
- 不把多商户扩写为无限租户平台，不提前引入复杂分账、生产级多地域或常驻大集群；
- 继续一次只做一个可验证闭环，代表多实例最多 3 个；
- 任何理想环境能力以覆盖方式补充当前单机证据，不反向阉割 M0–M8 已成立的机制。
