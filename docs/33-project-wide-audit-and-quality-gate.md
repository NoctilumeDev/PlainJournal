# 全项目审查与质量门禁

> 审查日期：2026-07-21  
> 审查范围：当前 Git 基线与全部有意保留的未提交工作树  
> 结论：M0–M5 全量回归已关闭；未发现未解决的 P0/P1 缺陷

## 1. 审查原则

本轮没有用最近提交代替当前完成度，也没有执行 `reset`、`checkout`、`clean`、暂存、提交或删除用户成果。审查覆盖：

- 11 个 Maven Reactor 模块和八个应用；
- 顾客端、管理端、共享 foundation 与 Playwright；
- Gateway、JWT/RBAC、内部服务身份和所有者隔离；
- 本地事务、Outbox、RocketMQ、幂等、补偿、对账和消费失败；
- M4 Payment、履约、售后、管理端和浏览器大整数契约；
- README、总计划、版本矩阵、服务文档与 M1 至 M4 专题证据；
- 真实 MySQL、Redis、Nacos、RocketMQ、MinIO 冒烟。

## 2. 最终质量门禁

### 2.1 后端

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn clean verify
```

| 模块 | 测试 |
| --- | ---: |
| platform-common | 7 |
| ecommerce-gateway | 8 |
| identity-service | 7 |
| catalog-service | 3 |
| inventory-service | 23 |
| trade-service | 66 |
| payment-service | 33 |
| fulfillment-service | 17 |
| marketing-service | 7 |
| **合计** | **179** |

50 份 Surefire 报告，0 失败、0 错误、0 跳过；全部应用完成可执行 JAR 打包。

独立静态门禁：

```powershell
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
```

PMD 7.17.0 检查 11 个 Reactor 模块，0 违规。PMD 未绑定到 `verify`，因此仍需显式运行。

SpotBugs 诊断：

```powershell
mvn --% com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs -Dspotbugs.effort=Max -Dspotbugs.threshold=Low -Dspotbugs.xmlOutput=true
```

- 9 份报告，179 条；
- Priority 1 为 0，全部 Rank 18/19；
- 138 条 `EI_EXPOSE_REP2`、14 条 `EI_EXPOSE_REP`，对应 Spring 依赖注入和 DTO/record 引用边界；
- 35 条 `THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION`、6 条 `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION`，对应 RocketMQ、回调和函数式接口显式异常契约；
- 没有空指针、锁、竞态、资源泄漏或注入类报告。

SpotBugs 在本项目作为诊断工具，不把框架边界告警机械改写成防御性复制或吞异常；新增高优先级或新类型仍必须单独处理。

### 2.2 前端

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\frontend
pnpm check
```

- foundation 18 个 Vitest；
- storefront 50 个 Vitest；
- admin 2 个 Vitest；
- 合计 70 个 Vitest；
- 2 个 Playwright E2E；
- 两端 TypeScript/Vue 类型检查通过；
- 两端生产构建通过；
- 关键页面 axe serious/critical 违规为 0；
- 浏览器 page error 为 0。
- `pnpm audit --registry https://registry.npmjs.org` 无已知漏洞。

本机永久配置使用 `D:\Node.js\current`，当前 Node.js 24.14.0 / pnpm 11.9.0；没有第二个 `NODEJS_HOME` 与其打架。本轮前端门禁和 `pnpm audit` 均使用该运行时。

### 2.3 仓库静态门禁

- `git diff --check` 无空白错误，仅有既有 LF/CRLF 提示；
- 全部 PowerShell 脚本通过 Parser；
- 主代码未发现未解释的 `TODO/FIXME/HACK`、`printStackTrace` 或 `System.out`；
- Markdown 相对链接检查无断链；
- 本机凭据文件由 `.gitignore` 排除；
- 最终没有残留八应用端口或本轮 Java 进程。

## 3. 真实运行证据

完整真实复验使用：

```powershell
.\run-foundation-smoke.ps1 `
  -SkipNetworkPreflight `
  -EnableRedisFaultInjection `
  -EnableObservability `
  -EnableDistributedTracing `
  -EnableSynchronousResilienceFaultInjection `
  -EnableTradeMarketingResilienceFaultInjection `
  -EnableCapacityBaseline `
  -CapacityRequests 1000 `
  -CapacityConcurrency 100 `
  -CapacityInventorySuccesses 100 `
  -CapacityTradeSuccesses 100
```

结果：

- 八应用健康、Nacos 路由、Request ID、Flyway；
- 注册登录、地址所有权、Catalog 发布、MinIO 签名媒体；
- MySQL 库存竞争、预占幂等、Outbox；
- 购物车、权威结算、幂等订单、取消释放；
- Payment 签名回调和回调幂等；
- 该历史阶段验证的是 `PaymentSucceeded -> OrderPaid -> Inventory CONFIRMED /
  Fulfillment CREATED`；当前权威顺序已收紧为 `PaymentSucceeded ->
  PAYMENT_CONFIRMING -> Inventory CONFIRMED -> OrderPaid -> Fulfillment CREATED`，
  见 [进入 M9 前审查](69-m0-m8-pre-m9-three-layer-audit-20260728.md)；
- 拣货、打包、发货、物流、签收、Trade `COMPLETED`；
- 整单售后、退货验收、库存回补、退款派发、签名退款回调；
- Payment 授权补偿和四域 `OPEN -> RESOLVED` 对账；
- Redis 失败锁、Gateway 限流、安全指标采集。
- Inventory 1000 请求/100 并发严格为 100 个 `RESERVED`、900 个 `REJECTED`，P95 1233.78 ms、76.19 RPS；
- Trade 1000 请求/100 并发严格为 100 个初始可支付、900 个缺货关闭，P95 581.73 ms、44.38 RPS；
- 同订单键、同支付回调、同退款回调各 100 路并发只形成一份跨域事实；
- 支付链 29.067 秒收敛，Trade Outbox 未发布数归零，未付款预占在收敛期间过期数为 0；
- Payment→Trade 和 Trade→Marketing 同步故障矩阵均验证超时、有限重试、熔断/半开恢复和零脏写；
- Prometheus 配置、12 条规则、四个实时目标、Alertmanager、Grafana Prometheus/Tempo 数据源与看板、Tempo 两条代表 trace 均通过。

本轮使用 `-SkipNetworkPreflight` 的原因是执行当时外部机器诊断脚本硬编码“必须恰好 6 个容器”，而完整冒烟需要 MinIO 作为第 7 个容器。执行前已完成等价人工网络、代理、容器和端口检查；没有修改机器级脚本、路由、网卡跃点、Docker 数据或镜像源。该机器级脚本已于 2026-07-22 修复为按名称验证 7 个必需容器并允许额外实验容器，本文保留当时的历史执行原因。

库存结果未知另以独立故障场景验证：Inventory 已在 MySQL 提交预占并返回 HTTP 200 后，代理丢弃响应；Trade 使用原预占号查询权威事实恢复到 `PENDING_PAYMENT`，只有一份订单、一份预占和一次库存扣留。

M4 权威结算、Payment 和 Fulfillment 还分别通过专项脚本：

- 权威结算使用购物车快照价 189 元、Catalog 当前价 199 元、Inventory 可用 5 件和 Marketing 398 - 20 = 378 元无副作用试算，生成一份订单/预占/权益锁，取消后库存与权益恢复；
- Payment 上游已返回 HTTP 200 后断开，前端按原支付键恢复唯一 `PROCESSING` 支付单，签名回调后 Payment `SUCCESS`、Trade `PAID`；
- Fulfillment 上游已返回 HTTP 200 后断开，前端查询恢复 `SIGNED`，历史 7 条、物流 3 条、Trade `COMPLETED`；
- 两条链路均验证跨账户 404 和超大业务 ID 字符串。
- Payment/Fulfillment 专项脚本现在默认自行启动和停止一次性本地代理，文档中的最简命令已复验，18601/18602 均无端口泄漏。

## 4. 审查发现与处理

### 已关闭：浏览器 DTO 污染内部事件

Trade 的 `OrderPaid` 曾把浏览器 `AddressSnapshotView` 直接放入 Outbox。该 DTO 的 `sourceAddressId` 使用 `ToStringSerializer`，导致内部事件从 JSON number 变成 string；Fulfillment 旧消费者只接受整数，真实冒烟因此无法创建履约单。

处理：

- Trade 为配送地址建立独立事件载荷；
- 回归测试读取 Outbox JSON 并断言 `sourceAddressId.isIntegralNumber()`；
- Fulfillment 兼容正整数 number 与十进制正整数字符串；
- 解析失败立即记录 `NEEDS_ATTENTION` 并 ACK；
- 业务异常继续有限重试，恢复后标记 `RECOVERED`。

同类检查确认其他 Outbox 使用独立 Map 或实体字段，没有继续复用带浏览器序列化注解的响应 DTO。

### 已关闭：旧核心消费者无限重试

Inventory/Fulfillment `OrderPaid` 与 Trade `PaymentSucceeded/FulfillmentEvent` 已接入已有 `ConsumerFailureRecorder`：

- 不可解析载荷直接进入终态失败台账；
- 业务失败按投递次数有限重试；
- 达阈值进入 `NEEDS_ATTENTION`；
- 成功消费标记 `RECOVERED`。

### 已关闭：Inventory 预占过期时间未进入幂等契约

同一 `reservationNo` 原请求哈希未包含 `expiresAt`，而 Trade 的结果未知恢复要求原过期时间一致。相同编号、相同订单/仓库/SKU/数量但不同过期时间可能错误返回旧结果，造成恢复长期未知。

处理：

- 内部预占命令强制显式提供未来的 `expiresAt`；
- `expiresAt` 与订单、仓库、SKU、数量共同进入请求哈希；
- 同号不同过期时间返回 `IDEMPOTENCY_CONFLICT`；
- 自动化增加缺失过期时间 400 和不同过期时间冲突；
- 真实冒烟的并发请求与幂等重试复用同一个稳定过期时间。

### 已关闭：M4 故障脚本依赖外部代理

Payment/Fulfillment 专项脚本文档原本允许直接执行，但干净环境下脚本没有自行启动故障代理，无法证明“上游已 200、下游响应丢失”。

处理：

- 默认分别在 18601/18602 启动一次性本地代理；
- 启动前检查端口并清除本轮旧 arm/evidence 文件；
- 等待 ready 文件后才创建业务夹具；
- `finally` 精确停止自身代理并清理 ready/arm；
- 保留 `GatewayBaseUrl`、`ProxyPort`、`ArmFile` 和 `ProxyEvidenceFile` 供外部代理与浏览器验收；
- 两个最简命令均已在无预置代理的环境复验，结束后端口释放。

### 已关闭：无效直接依赖

`platform-common` 删除未使用的 `spring-security-web`，按源码实际使用显式声明 `spring-boot`、`spring-security-core`、`spring-core` 和 `spring-web`。Trade/Payment 的 Starter、Caffeine、Resilience4j 和 Micrometer 由自动配置或运行机制使用，没有为“依赖数量好看”而误删。

`mvn dependency:analyze` 已完成但不作为自动删除依据：Spring Boot/Cloud Starter、JDBC Driver、Flyway、Nacos、Actuator、Tracing 和测试聚合依赖会因自动配置、SPI、反射或传递 API 被报告为“unused/used undeclared”。这类结果已结合源码、启动和真实故障验证审查；除上述 `platform-common` 直接依赖外，没有发现可安全机械删除且不改变运行机制的依赖。

### 保留边界：管理 API 不完整

当前前端没有伪造：

- Trade 通用管理订单列表；
- Payment 通用管理支付/退款列表；
- Catalog 草稿/下架列表；
- Marketing 规则/权益列表。

这些属于后续 API 演进，不影响 M4 已承诺的真实所有者域工作区。

## 5. 可以继续依赖的工程结论

- MySQL 是订单、库存、支付、履约、营销和退款的最终事实；
- Redis 不承担库存最终裁决；
- 跨服务继续使用本地事务、Outbox、RocketMQ、幂等、补偿和对账；
- 同步调用只在需要即时结果时使用，并有超时、熔断、舱壁和有限重试；
- 中间件或网络异常不会转换成业务成功；
- 浏览器 ID 字符串规则不会通过全局 ObjectMapper 影响事件；
- 顾客端与管理端都只在所有者域返回权威事实后显示成功；
- 2026-07-21 已在当前 M4 工作树上重新执行 1000 请求、100 并发正确性场景，不再只依赖 M3 历史报告。

## 6. 历史数字边界

`docs/23` 至 `docs/37` 中的 144、145、148、150、152、154 等测试数是各批次当时的证据，不回写。最新项目级数字只在 README、总计划、版本矩阵、本报告和 M4 毕业报告维护。

## 7. M0–M5 收口结论

M0–M5 的顾客交易体验、管理端所有者域工作区、自动 E2E、可访问性、真实结果未知恢复、同步韧性、追踪、四域对账、M3 多实例/发布治理、M5 容量与缓存门禁和完整五中间件闭环均已达到毕业条件。

多商户与 Go 服务继续等待 M9 的进入条件，不提前污染自营 B2C 主干。完整回归证据见 [M0–M5 全量回归与毕业收口](44-m0-m5-full-regression-20260721.md)；M4 业务收口见 [M4 顾客售后、管理端与毕业报告](40-m4-customer-after-sale-admin-and-graduation.md)。
