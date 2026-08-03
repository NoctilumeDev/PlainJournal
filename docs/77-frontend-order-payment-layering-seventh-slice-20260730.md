# 前端低耦合分层第七批：订单、支付与结果未知恢复

> 完成日期：2026-07-30  
> 范围：Trade 订单事实、Payment 用户意图、取消/支付竞态、所有者隔离、真实浏览器与 F12  
> 边界：不进入 M9；Fulfillment、售后和退款仍由各自所有者域裁决

## 1. 结论

第七批把订单中心从旧全局 store 和大体量详情 view 中拆成三块边界明确、视觉连续的
积木：

```text
entities/order
        ↓ 订单事实与展示状态
features/order-payment
        ↓ 创建、查询、结果未知恢复与动作互斥
pages/orders
        ↓ 会话、路由、订单列表/详情旅程装配
app/router
```

`entities/order` 只理解 Trade 订单事实；`features/order-payment` 只承担 Payment
意图和支付状态；page 负责把当前登录会话显式映射为访问上下文。它们都不反向读取
全局 session，也不复制后端状态机。旧 `stores/orders.ts`、`stores/payments.ts`
已经退出运行路径并删除。

页面仍是一条订单旅程，没有为了代码分层增加满屏卡片或重复状态。当前订单详情继续
组合既有履约与售后能力；后续再沿真实所有权逐块迁移，不按目录对称一次性重写。

## 2. 所有权、时序与竞态边界

### 2.1 账户切换与迟到响应

订单和支付 model 都接收显式：

```text
authenticated + ownerId + accessToken
```

请求发起时捕获 token、owner 和访问代次。A 账户请求晚于 B 账户返回时，旧结果不能
写入 B 的订单列表、详情或支付状态。待恢复支付使用 owner-scoped v2 键；旧 v1 数据
只允许同一 owner 迁移。业务 ID 始终以十进制字符串保存，不经过 JavaScript number。

### 2.2 支付创建结果未知

支付创建使用稳定 `payment:{uuid}` 和固定请求体。并发点击合并成一个活动 Promise。
网络、超时、非法响应或 5xx 不会被显示为成功，也不会换键重提；前端先按原键和订单号
查询 Payment：

- 找到权威事实：恢复并展示真实状态；
- 仍不能确认：保留 owner-scoped pending，明确显示结果未知；
- 账户、token 或请求代次已变化：丢弃旧响应。

真实故障代理在 Payment 上游已经返回 HTTP 200 后主动丢弃响应。浏览器随后按原键
恢复唯一支付事实 `PAY2082679475942666241 / PROCESSING`，没有生成第二笔 Payment。

### 2.3 取消与支付互斥

订单可取消并不只取决于 Trade 的 `PENDING_PAYMENT`。页面必须先完成 Payment 边界
读取；Payment 仍在加载、读取失败、存在 pending 创建，或已经是 `PROCESSING/SUCCESS`
时，取消入口失败关闭。创建支付与取消请求各自合并并发动作，响应身份还必须匹配原订单。

这只是前端防误触，不是最终裁决。Trade 取消前事实核对、Payment 唯一约束、MySQL
状态机、Outbox、RocketMQ、幂等消费和补偿仍共同决定最终结果。

## 3. 三层证据

本批所有结论均同时具备代码、自动化测试和真实运行证据：

| 高风险边界 | 代码证明 | 自动化测试 | 真实运行证据 |
| --- | --- | --- | --- |
| 订单所有者隔离 | 显式 owner/token/代次；响应身份校验；owner-scoped pending | model 覆盖 A 慢响应晚于 B、token 更新、跨 owner 恢复 | A 列表 1 单；B 列表 0 单；B 直达 A 订单为中文 404 |
| 支付幂等与结果未知 | 稳定键、固定请求快照、活动 Promise、按原键查询恢复 | model 与 Mock E2E 覆盖并发创建、响应丢失、刷新恢复 | 代理记录上游 200 后断开；MySQL 仅 `1|1|PROCESSING` 一笔事实 |
| 取消/支付竞态 | Payment 未就绪、失败、pending、处理中或成功时取消失败关闭 | 单测覆盖状态矩阵及并发动作合并；E2E 覆盖按钮互斥 | 支付前创建/取消均可见；恢复为 PROCESSING 后二者均隐藏 |
| 资金、库存与履约收敛 | Payment 回调验签与幂等、Trade/Inventory/Fulfillment 消费状态机 | 435 个后端测试及对应服务集成测试 | 签名回调及重复回调后 Payment SUCCESS、Trade PAID→FULFILLING、库存 CONFIRMED、履约 CREATED |
| 权限与数据最小化 | 所有者域 404；浏览器存储不保存密码/JWT/支付请求键 | Storefront model、E2E 和类型门禁 | 跨账户读取/创建隐藏；浏览器证据中密码、JWT、pending key 均为 0 |
| 页面与响应式可信度 | 状态只来自权威响应；错误中文化；动作按事实展示 | 14 个 Mock E2E、类型检查、构建和 axe | 桌面/移动真实操作、F12、console error 均通过；375px 客户区无水平溢出 |

自动化测试不能替代真实中间件，日志也不能替代数据库、网络和页面事实。本批真实结论
同时读取故障代理、浏览器网络/DOM、MySQL 领域事实和最终清理结果。

## 4. 真实浏览器与 F12

按单机资源边界串行启动：

```text
7 个 core 中间件
Gateway + Identity + Catalog + Inventory
Trade + Payment + Fulfillment + Marketing
Storefront Vite + 一次性响应丢失代理
```

每个 JVM 使用 `-Xms64m -Xmx256m -XX:ActiveProcessorCount=4`，浏览器只使用一个
worker。本批没有并行启动观测栈、压力测试或其他项目。

内置浏览器验证结果：

- A 账户订单列表为 1，中文商品规格和地址快照正确；
- 支付前订单为 `PENDING_PAYMENT`，创建支付和取消入口均可见；
- Payment 上游已返回 200 后连接被故障代理切断；
- 页面按原键恢复为 `PROCESSING`，显示唯一 Payment 编号；
- 此时创建支付和取消均不可用，只保留刷新支付状态；
- 退出后返回 Catalog 支撑的健康首页；
- B 账户列表为 0，直接访问 A 订单返回“订单不存在，或不属于当前账户。”；
- 390×844 请求视口下浏览器实际客户区为 375px，`scrollWidth` 与
  `bodyScrollWidth` 均为 375，无水平溢出；
- 页面错误、console error 均为 0。

浏览器证据不保存密码、JWT 或 pending payment key。故障代理控制证据为了证明
“同一幂等键的原请求已到达上游”保留请求键和请求体摘要，但不包含令牌或密码。

最终证据：

- `backend/.run/frontend-order-payment-seventh-20260730-r2/browser/browser-verification.json`
- `backend/.run/frontend-order-payment-seventh-20260730-r2/payment-create-proxy-evidence.json`
- `backend/.run/frontend-order-payment-seventh-20260730-r2/payment-verification.out.log`
- `backend/.run/frontend-order-payment-seventh-20260730-r2/workspace-verification.json`

## 5. 真实跨服务收敛

浏览器完成响应丢失恢复后，验证脚本继续发送签名渠道回调和重复回调，并从所有者数据库
读取最终事实：

```text
创建响应丢失前后 Payment 事实     1|1|PROCESSING
重复回调后 Payment 事实           1|1|1|1|SUCCESS
Trade 支付历史                    1
Trade 状态                        PAID → FULFILLING
Inventory reservation             CONFIRMED
Fulfillment                       CREATED
相关 consumer_failure             0
跨账户 Payment 读取/创建          404
```

因此“页面恢复成功”不是由日志推断；它与 Payment、Trade、Inventory 和 Fulfillment
的数据库事实及重复回调幂等结果一致。

## 6. 本批发现并修复的问题

真实运行和全量门禁发现的问题分为产品代码与验证工具两类：

- Payment 监听重复触发同一查询，已收敛为单一加载路径；
- 已知不存在的订单仍在首次装配时产生冗余 404，现由显式 absent 集合抑制；
- 跨账户订单错误仍显示英文，现统一为中文所有者隐藏信息；
- Mock E2E 在退出动作完成前跳转，偶发把登录态购物车误当游客袋，现等待 URL 与登录入口；
- Identity 测试 namespace 最长 24 位，真实总控 run id 已改为短且唯一；
- 固定睡眠无法可靠协调人工浏览器，现改为 fixture/continue 文件握手；
- 总控遗漏 Catalog 时，退出后的首页出现非业务故障，现按完整用户旅程启动 Catalog；
- SQL 夹具中文在命令行编码边界出现乱码，改为 `utf8mb4` 十六进制转换；
- 横向审查发现 Checkout、Payment 和确认收货三个人工浏览器模式会把一次性夹具密码
  打印到控制台；现统一只写短生命周期 fixture 文件并在 `finally` 删除，控制台只
  输出脱敏摘要，订单/Payment 总控检测到 password 字段会直接失败。

这些问题说明真实浏览器、F12 和全链路装配能够发现单元测试与服务日志看不到的
时序、环境和“只见树木不见森林”问题。

## 7. 最终门禁

```text
订单/支付定向 Vitest       25 passed
Foundation                 42 Vitest
Storefront                 87 Vitest
Admin                      12 Vitest
前端合计                  141 Vitest
Mock E2E                   14 Playwright，1 worker
分层规则                   12 passed
分层扫描                   60 files / 180 relative imports
typecheck                  三端 passed
build                      Storefront / Admin passed
后端 clean verify          100 reports / 435 tests / 0 failure
PMD                        12 reports / 0 violation
SpotBugs                   12 reports / 313 diagnostics
                            P1=0 / P2=247 / P3=66 / missing class=0
PowerShell AST             49 files / 0 error
MJS node --check            7 files / 0 error
Markdown                   85 files / 367 relative links / 0 broken
Compose                     8 expected core combinations passed
M9 前边界配置               10 owner schemas / 10 users / 0 global grants
git diff --check            passed
Git 跟踪生成物              0
旧订单/Payment 导入         0
```

生产构建：

```text
Storefront CSS 53.05 kB / gzip 8.63 kB
Storefront JS  290.27 kB / gzip 89.76 kB
Admin CSS      19.43 kB / gzip 3.91 kB
Admin JS       192.00 kB / gzip 61.89 kB
```

SpotBugs 的 P2/P3 是已分类诊断，不等于“零问题”；主要仍为 Spring 构造注入/DTO
引用暴露和显式异常边界。本批没有新增 P1，也没有缺失分析类。

## 8. 清理与下一边界

总控只停止它启动且命令行已核验的进程，精确清理 Trade 节点租约和测试事实。最终
业务端口、PlainJournal Java/Vite/代理进程、临时消费失败和测试数据均为 0；7 个
core 容器随后停止，Docker Desktop 关闭，数据卷保留。

订单和 Payment 已形成可组合积木。下一前端切片应先处理 Fulfillment 展示与确认收货
边界，再处理售后/评价；每一批继续执行代码、自动化、真实浏览器/F12 三层证据，
且重任务串行运行。

M9 三个商户与 Go 异构统计服务继续冻结，等待用户完成 M0–M8 复审并单独确认准入。
