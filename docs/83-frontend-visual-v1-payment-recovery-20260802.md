# 前端视觉 V1 支付恢复 P0 修复与三层验收

> 验收日期：2026-08-02（北京时间）  
> 状态：V1-001、V1-002 已关闭；V1 完成  
> 下一坐标：V2 设计令牌、基础 primitives 与全局壳层  
> 业务边界：只修复前端支付恢复，不修改后端资金状态机、API、消息契约或数据库结构

## 1. 结论

V1 审计发现的两项支付恢复 P0 已完成代码、自动化测试和真实运行三层验收：

- 支付创建结果未知并执行按原键恢复后，`creatingOrderNo` 与
  `resolvingSubmission` 均会归零，不再永久锁死按钮；
- 页面读取到同一订单的 Payment 权威事实后，会清除该订单的设备 pending 记录，
  不再同时展示已存在的 `PROCESSING` 支付单和“原键安全重试”；
- 不同订单的未决 pending 不会被误删，账户所有者隔离和原幂等键复用保持成立；
- 真实故障链证明上游创建已经返回 HTTP 200、浏览器响应被丢弃、页面按权威查询恢复，
  后续签名回调和 Trade、Inventory、Fulfillment 事实全部收敛；
- 本轮没有把中间件异常、浏览器请求失败或日志文本冒充业务成功。

因此 [V1 审计报告](82-frontend-visual-v1-audit-20260801.md) 中的 V1-001、
V1-002 已关闭，视觉重构可以进入 V2。

## 2. 代码证明

涉及文件：

```text
frontend/storefront-web/src/features/order-payment/model/paymentStore.ts
frontend/storefront-web/src/features/order-payment/model/paymentStore.test.ts
frontend/e2e/specs/m4.spec.ts
```

修复边界：

1. 支付创建使用独立 `createRevision` 管理创建请求生命周期；
2. pending 查询继续使用 `submissionRevision` 管理恢复查询；
3. 内层恢复查询不再使外层创建请求的 `finally` 失效；
4. `completeSubmission` 只在权威 Payment 与设备 pending 属于同一订单时清除
   pending；不同订单 pending 保持未决；
5. `loadForOrder`、按幂等键恢复和创建成功统一经过同一权威收敛入口。

该实现没有通过“无条件清空所有 pending”掩盖竞态，也没有改变支付成功、失败或
处理中状态的业务含义。

## 3. 自动化测试

### 3.1 定向回归

Payment Store：

```text
1 test file
11 tests
0 failed
```

新增或强化的关键断言覆盖：

- 丢失创建响应后按原键查询恢复；
- 创建与查询均未确认时按钮忙碌状态归零；
- 安全重试继续使用同一个幂等键；
- 读取同订单 `PROCESSING` 权威事实后清除 pending；
- 读取另一订单事实时不清除原订单 pending；
- 账户切换后 pending 继续按所有者隔离。

### 3.2 完整前端门禁

2026-08-02 在当前工作树重新串行执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\frontend
pnpm check:boundaries
pnpm typecheck
pnpm test
pnpm build
pnpm e2e
```

结果：

| 门禁 | 结果 |
| --- | ---: |
| 分层规则 | 15 / 15 |
| Foundation Vitest | 42 |
| Storefront Vitest | 121 |
| Admin Vitest | 12 |
| Vitest 合计 | 175 |
| Playwright | 15 / 15 |
| TypeScript / Vue 类型检查 | 通过 |
| Storefront / Admin 生产构建 | 通过 |

Playwright 新场景明确验证：

- 首次创建返回不确定结果后，页面不残留“正在确认”按钮；
- 两个安全重试入口均可用；
- 第二次 POST 继续使用第一次的幂等键；
- 读取 `PROCESSING` 权威事实后安全重试入口消失；
- 设备 pending 清除，取消入口关闭；
- 页面错误为 0，预期的 503/404 不被误判为前端崩溃。

## 4. 真实浏览器与真实服务链

### 4.1 运行边界

证据目录：

```text
backend/.run/v1-payment-real-20260802-150952/
```

本轮先通过 [本地开发网络基线](07-local-development-network.md) 的宿主机门禁，
随后串行启动 7 个核心容器，再启动受限工作区：

- 8 个应用，每个 JVM `-Xms64m -Xmx256m`；
- `ActiveProcessorCount=4`；
- 一个 Storefront Vite；
- 一个只丢弃指定支付创建响应的故障代理；
- 一个 TCP/UDP 动态端口监控。

现有 JAR 均晚于对应后端源码，本轮没有 Java、数据库或消息契约修改，因此使用当前
JAR 进行真实复验，没有用重新打包掩盖前端缺陷。

### 4.2 浏览器事实

内置浏览器通过真实 Identity 注册账户登录订单
`ORDM4PAYBEAE4E751C5C`，初始页面显示：

- Trade `PENDING_PAYMENT`；
- Payment 尚未建立；
- “创建支付单”和“取消订单”均可见。

点击一次“创建支付单”后，故障代理记录：

```text
POST /api/v1/payment/payments
upstreamStatus = 200
upstreamResponseBytes = 329
```

代理在收到完整上游 HTTP 200 后丢弃浏览器响应。页面随后通过原幂等键查询恢复，
实际显示：

- 唯一 Payment `PAY2083814870399004674`；
- 状态 `PROCESSING`；
- 金额 `¥378.00`；
- “取消订单”消失；
- 不存在残留“正在确认”按钮；
- 不存在旧 pending 的“查询并使用原支付键安全重试”。

该运行证据来自页面可见事实和故障代理字节级记录，不依赖应用日志推断。

### 4.3 四域事实

浏览器确认完成后，脚本继续执行签名回调并直接检查 MySQL 与跨服务状态：

| 事实 | 结果 |
| --- | --- |
| 响应丢失后恢复状态 | `PROCESSING` |
| 回调后 Payment | `SUCCESS` |
| Payment 行 / 交易 / 回调 / 成功 Outbox | `1 / 1 / 1 / 1` |
| Trade 支付历史 | 1 |
| Trade 下游状态 | `FULFILLING` |
| Inventory 预占 | `CONFIRMED` |
| Fulfillment | `CREATED` |
| 跨账户查询与创建 | 404 隐藏 |
| 相关消费失败 | 0 |

这证明前端恢复没有制造第二笔支付，也没有把 `PROCESSING` 提前显示为成功。

## 5. 网络与机器边界

本轮开始前，2026-08-02 14:43:52 的 TCP `4231` 仍位于 15 分钟门禁窗口，项目
按规则拒绝启动 Docker。等待窗口退出并重新通过门禁后才继续。

真实工作区的 600 秒监控结果：

```text
497 samples
peak TCP connections = 711
peak TCP dynamic unique ports = 401
peak UDP endpoints = 64
peak UDP dynamic unique ports = 44
new 4231/4266 = 0
```

启动、浏览器操作和完成清理阶段的前台补充监控也均无新事件。该结果只证明本轮缩比
真实链没有触发动态端口故障，不反推 14:43 的历史 `4231` 根因。

## 6. 清理反证

真实验证通过后再次直接查询数据库：

```text
Identity users = 0
Trade orders / outbox = 0 / 0
Payment orders / outbox = 0 / 0
Inventory reservations / warehouses = 0 / 0
Fulfillment orders = 0
related consumer failures = 0
```

工作区清理后：

- PlainJournal Java 进程为 0；
- `18000`、`18101–18107`、`18200`、`18601` 监听为 0；
- 7 个核心容器按逆序逐个停止；
- Docker Desktop 已停止；
- 未删除容器、卷、镜像或中间件数据。

## 7. V2 准入

V1 支付 P0 已关闭。V2 继续遵守以下硬边界：

- 只建立基础令牌、primitives 和全局壳层；
- 不修改 API、状态机、幂等键、权限或结果未知语义；
- 状态色保持语义稳定，不随青荷/素白主题改变含义；
- 首页、商品详情和订单详情仍在 V3 作为三种代表原型统一验收；
- 管理端统计夹具、移动溢出和 `NEEDS_ATTENTION` 视觉证据继续按 V1 问题矩阵
  进入对应 V3/V6 切片，不在 V2 顺手扩张业务范围。
