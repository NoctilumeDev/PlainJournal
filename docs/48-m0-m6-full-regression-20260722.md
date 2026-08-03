# M0–M6 全量回归与毕业收口

> 回归日期：2026-07-22  
> 状态：已完成  
> 范围：当前工作树、M0–M6 自动化、真实中间件、故障、多实例、容量、前端与静态审查

## 1. 代码质量门禁

正式证据：

```text
backend/.run/m0-m6-final-quality-gates-20260722-r1
```

结果：

- `mvn dependency:analyze` 成功；报告中的 Starter、自动配置、SPI、驱动和测试聚合依赖按框架边界审查，未发现可安全机械删除的生产依赖；
- PMD Maven Plugin 3.28.0 / PMD 7.17.0：全 Reactor 0 违规；
- SpotBugs Maven Plugin 4.9.8.2 / SpotBugs 4.9.8：9 份报告、205 条诊断，其中 158 条 Rank 18 / Priority 2、47 条 Rank 19 / Priority 3，Priority 1 为 0；最终 XML 保存在 `backend/.run/m0-m6-final-quality-gates-20260722-r1/spotbugs-final`；
- `mvn clean verify`：54 份 Surefire 报告、204 个测试，0 失败、0 错误、0 跳过；
- `pnpm check`：Foundation 18、Storefront 50、Admin 2，共 70 个 Vitest；2 个 Playwright E2E；两端类型检查和生产构建通过；
- `pnpm audit`：无已知漏洞。

自动化使用 H2 MySQL compatibility mode 和受控浏览器夹具，只证明代码、事务与契约分支，不替代以下真实证据。

## 2. M0–M2 基础、交易与治理

正式证据：

```text
backend/.run/m0-m6-foundation-regression-20260722-r4
backend/.run/m0-m6-inventory-response-loss-20260722-r1
```

结果：

- Gateway 与八个应用健康，Nacos 路由、请求 ID 和配置来源通过；
- 七个业务 schema 的 MySQL/Flyway、Identity、Catalog、MinIO、JWT/RBAC 通过；
- 正向下单、营销、支付、库存确认、履约、签收，以及逆向售后、回补、退款全部通过；
- Payment、Inventory、Trade、Fulfillment 四个所有者域对账检测和恢复通过；
- Inventory 已提交预占但 HTTP 响应丢失后，Trade 按同一预占号查询权威事实恢复，只有一套预占与流水；
- Prometheus、Alertmanager、Grafana、Tempo 真实栈通过，Prometheus 为五个实时目标、16 条规则。

## 3. M3 多实例与发布治理

正式证据：

```text
backend/.run/m3-real-regression-20260722-r1
```

结果：

- Trade Outbox 1/2/3 实例抢占与租约通过；
- Trade 容器 1/2/3 实例、健康和优雅停机通过；
- 消费者 1/2/3 实例竞争、重复投递和三个进程终止边界通过；
- Gateway 滚动升级、失败候选摘除与稳定版回退通过；
- Git HEAD 稳定版与当前工作树候选版双版本兼容通过。

审查发现 M6 新增 Trade Producer Topic 后，旧隔离 RocketMQ 专项只创建普通测试 Topic，会导致 Producer 整体启动失败。现已让每次实验创建独立普通 Topic 和秒杀 Topic，并将 Topic 写入证据；修复后消费者 1000 条专项完整通过。

## 4. M4 产品链路专项

正式证据：

```text
backend/.run/m4-real-regression-20260722-r1
```

结果：

- 权威结算、不可变价格/地址/商品/分摊快照和取消释放通过；
- Payment 创建 HTTP 200 响应丢失后，前端按稳定幂等键查询恢复并最终支付成功；
- Fulfillment 确认收货 HTTP 200 响应丢失后，按所有者域查询恢复为 `SIGNED`，Trade 最终 `COMPLETED`；
- 物流历史和轨迹保持追加式，跨账户事实隐藏。

## 5. M5 容量与缓存

M5 查询、写链、Catalog 多级缓存和 M5.5 仓库治理的历史证据继续有效，详见：

- [M5 容量方法与第一批基线](41-m5-capacity-methodology-and-first-baseline.md)
- [M5 查询容量、订单分页与 N+1 收敛](42-m5-query-capacity-and-order-pagination.md)
- [M5 写链容量与 Catalog 多级缓存](43-m5-write-capacity-and-catalog-cache.md)
- [M5.5 仓库治理与进入下一阶段门禁](45-m5-5-repository-governance.md)

本轮基础烟测再次完成真实 1000 请求/100 并发库存与订单竞争；库存数量、订单结果、同键幂等和跨域事实正确。性能数字受本机同时运行的中间件和调度收敛影响，只作为当前回归水位，不替代 M5 固定环境正式曲线。

## 6. M6 秒杀与峰值

正式证据：

```text
backend/.run/m6-flash-sale-admission-final-20260722-r1
backend/.run/m6-flash-sale-queue-final-20260722-r4
```

结果：

- 1000 请求/100 并发严格 100 准入、900 售罄；
- 同用户 100 并发只占一个名额；
- Gateway 上限 20 时严格 20 放行、80 限流；
- Redis 停机时准入 7 ms 返回 `503`，普通查询 `200`，恢复后准入 `202`；
- MQ 停机期间新增接受事实保留在 Outbox；
- 恢复后 Marketing 与 Trade 各完成 101 条，Inventory `on_hand=101,reserved=101`；
- 未发布、处理中、失败、结果未知和需人工处理均为 0；
- 普通下单、支付创建、退款查询混合峰值 300/30 全部成功，P95 873.21 ms。

完整机制和边界见 [M6 秒杀排队、最终裁决与毕业报告](47-m6-flash-sale-queue-and-graduation.md)。

## 7. 收尾状态

- 工作树中的大规模修改与新增文件均为当前项目成果，未执行 `reset`、`checkout` 或 `clean`；
- 所有 Docker 与中间件操作均遵守 [本地开发网络](07-local-development-network.md)，没有修改网卡跃点、系统路由、代理或 Docker 数据；
- 业务 JVM 和 18000–18107 端口已释放；
- M3 临时容器、网络、隔离 RocketMQ、M6 Topic/消费组和 Redis 命名空间已清理；
- 最终只读查询按本次固定用户、商品、SKU 和运行命名空间复核 Identity、Catalog、Marketing、Trade、Inventory，残留 MySQL 事实合计为 0；RocketMQ 动态 Topic/消费组和两个 Redis 命名空间残留也均为 0；
- MySQL、Redis、Nacos、RocketMQ NameServer/Broker/Proxy、MinIO 七个核心中间件按本地开发基线保留运行，MySQL 与 Redis healthy；
- 24 个 PowerShell 脚本全部通过 Parser，56 个 Markdown 文件无失效相对链接；
- 526 个生产源码文件中无独立 TODO/FIXME/HACK、`System.out`、`System.err` 或 `printStackTrace`，448 个生产 Java 文件均有有效类型声明；
- `target/dist/node_modules/.run/coverage/playwright-report/test-results` 无文件误入版本库，`git diff --check` 通过；
- Node 唯一解析为 `D:\Node.js\current\node.exe` 24.14.0，`NODE_HOME=D:\Node.js\current`，`NODEJS_HOME` 未设置，PATH 中只有一个 Node 目录。

M6 两次正式专项中的机器级网络预检返回码为 1，原因是执行当时仓库外
`D:\DevTools\Network\check-dev-network.ps1` 仍按旧基线要求“恰好 6 个容器”。专项随后直接验证
7 个核心中间件和完整业务事实，未发现路由或代理故障。该机器级脚本已于 2026-07-22 修复为
按名称验证 7 个必需容器并允许额外容器；历史证据中的返回码保持原样。

M0–M6 已形成当前代码状态下可复验的证据闭环。下一阶段可以进入 M7，但必须继续遵守“机制真实、证据真实、规模缩比”，不能把本机结果外推为生产容量承诺。
