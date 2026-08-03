# M0–M5 全量回归与毕业收口

> 回归日期：2026-07-21（本机部分运行日志跨本地日界线，证据目录统一按 20260721 编号）  
> 状态：已完成  
> 范围：M0、M1、M2、M3、M4、M5 当前工作树与真实本地中间件

## 1. 代码与自动化门禁

最终代码状态执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn clean verify
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
mvn --% com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs `
  -Dspotbugs.effort=Max -Dspotbugs.threshold=Low -Dspotbugs.xmlOutput=true

cd C:\Users\lenovo\Desktop\PlainJournal\frontend
pnpm check
pnpm audit --registry https://registry.npmjs.org
```

结果：

- Maven Reactor 11 个模块全部 SUCCESS；
- 50 份 Surefire 报告、179 个测试，0 失败、0 错误、0 跳过；
- PMD 7.17.0 全 Reactor 0 违规；
- SpotBugs 4.9.8：9 份报告、179 条诊断，Priority 1 为 0，全部 Rank 18/19；类型为 Spring/DTO 引用暴露和显式异常边界；
- 前端 70 个 Vitest（Foundation 18、Storefront 50、Admin 2）、2 个 Playwright E2E、两端类型检查和生产构建全部通过；
- `pnpm audit` 无已知漏洞。

本轮还将 Catalog 缓存等待 Future 的宽泛 `catch (Exception)` 收窄为
`ExecutionException | TimeoutException | CancellationException`，保留容量保护语义并消除新的宽泛异常诊断。

## 2. 真实五中间件与业务闭环

使用 `backend/run-foundation-smoke.ps1`，参数包含：

- Redis 运行时停机与恢复；
- Prometheus、Alertmanager、Grafana、Tempo 和专用 metrics 身份；
- 分布式追踪；
- Payment→Trade、Trade→Marketing 同步超时/重试/熔断/舱壁；
- 1000 请求、100 并发的库存与订单竞争；
- 同订单、同支付回调、同退款回调幂等；
- 正向支付、履约、签收、逆向售后、退款和四域对账。

基础烟测结果为 PASS。真实链路中的关键结果：

- Inventory 竞争：100 成功预占、900 拒绝；
- Trade 竞争：100 个初始可支付订单、900 个缺货关闭；
- 同订单/支付/退款回调均收敛为单一跨域事实；
- Redis 运行时停机仍可由 Catalog 回源 MySQL；
- Payment 与 Refund 的代表 trace 在 Tempo 中同时包含 `payment-service`、`trade-service` 和 RocketMQ PRODUCER/CONSUMER span；
- Trade、Fulfillment、Payment、Inventory 四域对账检测与恢复全部通过。

证据由脚本输出和数据库事实共同构成，不以日志单独作为正确性结论。

## 3. M4 专项复验

以下专项在 8 个应用真实健康后串行执行并通过：

- `verify-m4-authoritative-checkout.ps1`
- `verify-m4-payment-recovery.ps1`
- `verify-m4-fulfillment-timeline.ps1`

覆盖权威结算、不可变价格快照、库存/营销锁定与释放、支付响应未知、签收完成、物流轨迹、售后和退款状态推进。

## 4. M3 多实例与发布治理

以下专项使用真实 Docker/Java/Nacos/RocketMQ，并限制在 1/2/3 实例：

- `verify-trade-outbox-multi-instance.ps1 -SkipNetworkPreflight`
  - 1/2/3 实例均 1000/1000 Outbox 发布；
  - pending/publishing 均为 0；
  - 顺序、重复、死锁和死租约恢复断言通过。
- `verify-trade-container-multi-instance.ps1 -SkipNetworkPreflight`
  - 容器化 Trade 1/2/3 实例、健康、优雅停机和 Outbox 门禁通过。
- `verify-trade-consumer-multi-instance.ps1 -SkipNetworkPreflight`
  - PaymentSucceeded 消费者 1/2/3 实例均确认 1000/1000，重复确认 0；
  - 重复投递和进程终止恢复通过。
- `verify-gateway-rolling-upgrade.ps1 -SkipNetworkPreflight -SkipBuild`
  - 基线、候选加入、摘流、失败候选数据库拒绝和回滚全部通过；
  - 探测请求 1,357+，失败 0。
- `verify-trade-dual-version-compatibility.ps1 -SkipNetworkPreflight`
  - Stable V5 与 Candidate V12 真实兼容；
  - 旧字段回填和双向事件均为 `PUBLISHED|PAID|1|1|1`；
  - 探测请求 1,374+，失败 0。

审查过程中明确修正了两个实验干扰源：Outbox 探针必须停止常驻 Trade 实例，滚动升级必须释放宿主 Gateway 端口。修正后只采纳隔离重跑结果。

## 5. M5 容量、缓存与基线收口

M5 正式证据仍以以下目录为准：

```text
backend/.run/m5-write-capacity-baseline-final-corrected-20260721-r6
backend/.run/m5-catalog-cache-smoke-20260721-r9
```

- 写链矩阵 113 份结果与 correctness gates 全部通过；
- Catalog Caffeine L1 + Redis L2 + MySQL 事实源门禁全部通过；
- 热点、负缓存、逻辑过期、Pub/Sub 失效、Redis 运行时停机回源和重建背压均无 500/传输错误。

最终执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\tools\prepare-m5-baseline-data.ps1 -Action Remove
```

Remove 输出中 Catalog、Identity、Trade、Inventory 各表基线计数均为 0，未留下确定性 M5 数据。

## 6. 环境与收尾状态

- `docs/07-local-development-network.md` 已在所有 Docker/中间件操作前重新阅读；
- 执行当时网络诊断脚本仍按旧“6 个容器”规则运行，因此本轮相关专项使用显式 `-SkipNetworkPreflight`；没有修改网卡跃点、系统路由、Docker 数据或代理。该机器级脚本已于 2026-07-22 修复为按名称验证 7 个必需容器并允许额外容器；
- Node.js 为 `D:\Node.js\current\node.exe` v24.14.0，pnpm 11.9.0；
- 8 个业务端口已释放，PlainJournal Java 进程为 0；
- MySQL、Redis、Nacos、RocketMQ NameServer/Broker/Proxy、MinIO 保持运行且 MySQL/Redis healthy；
- MySQL `Innodb_log_waits=0`、`Slow_queries=0`、`OOMKilled=false`；M5 清理期间已知的 MySQL 自动重启次数仍为 1；
- 观测专项验证后按脚本约定清理 Prometheus、Alertmanager、Grafana、Tempo 临时容器；真实观测证据已保存，不把“当前未常驻”解释为未验证。

M0–M5 现已形成可复验闭环。后续进入新阶段前，仍应以本文件和各专项证据目录为基线，不把单机缩比结果外推为生产规模容量承诺。
