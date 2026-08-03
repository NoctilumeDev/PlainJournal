# 前端低耦合分层第六批：权威结算、竞态隔离与权限纵深

> 完成日期：2026-07-30  
> 范围：Checkout feature、结算 page、权威价格/库存/优惠复核、幂等下单恢复和所有者隔离  
> 边界：不进入 M9，不改变四个领域的最终裁决权，不把支付或履约状态机并入 Checkout

## 1. 结论

第六批把原先由路由页面和全局 store 共同持有的结算流程收敛为一块可组合但不泄漏
内部状态的 feature：

```text
entities/account-cart
entities/address
        ↓ 显式事实与访问上下文
features/checkout
        ↓ 公开 CheckoutWorkspace
pages/checkout/CheckoutPage
        ↓ 路由与会话装配
app/router
```

视觉仍是一条连续结算旅程，没有为了代码分层增加卡片、边框或重复步骤。分层改变的
是所有权和依赖方向：

- `CheckoutPage` 只把当前登录会话映射成显式访问上下文，并处理登录跳转；
- `CheckoutWorkspace` 拥有跨 Trade、Identity、Catalog、Inventory、Marketing 的
  结算交互；
- checkout model 不读取 customer-session，不持有路由，也不把购物车快照冒充权威
  价格或库存；
- 外部模块只能从 `features/checkout/index.ts` 使用公开能力；
- 旧 `stores/checkout.ts` 与 `views/CheckoutView.vue` 已退出运行路径。

## 2. 状态、时序与事务边界

### 2.1 所有者与访问代次

结算状态使用 `ownerId + accessToken + accessRevision` 标识访问上下文。owner 或 token
变化会立即使旧读取、旧复核和旧提交失效；A 的迟到响应不能写入 B 的页面。API
客户端也使用请求发起时捕获的 token，而不是在响应阶段重新读取当前会话。

待恢复订单从旧全局键迁移为：

```text
plain-journal:pending-order:v2:<ownerId>
```

只允许同一 owner 迁移自己的 v1 历史记录。另一账户看不到、不能认领，也不会被旧
账户的 pending 请求阻塞。

### 2.2 草稿指纹与权威复核

购物车、地址、地区或权益选择改变时，结算草稿指纹随之改变。价格试算和三域权威
复核同时捕获 `calculationRevision + draftFingerprint`；旧响应即使最后到达，也不能
重新点亮提交按钮或覆盖新草稿。

页面先展示 Trade 购物车快照，再显式读取：

1. Catalog 当前商品与 SKU 价格；
2. Inventory 当前可用库存；
3. Marketing 当前资格和价格试算。

复核只在 60 秒内有效。价格从 ¥189 变为 ¥199 时，2 件商品会从快照 ¥378 明确更新
为权威原价 ¥398；库存不足、商品不可售或请求结果未知都不会伪造成可下单。

### 2.3 下单与结果未知

订单提交继续使用稳定 `order:{uuid}` 幂等键和固定请求快照。并发点击会合并为同一个
活动 Promise，只产生一个 Trade POST。网络、超时、非法响应或 5xx 后先按原键查询
Trade；仍不能确认时保存 owner-scoped pending，不换键盲目重提。

前端的串行和代次控制只解决浏览器竞态，不能代替服务端正确性。最终事实仍由：

- Trade 本地事务、订单状态机和幂等键唯一约束；
- Inventory MySQL 条件更新、预占流水和状态机；
- Marketing 权益锁定、分摊与状态机；
- 跨域 Outbox、RocketMQ、幂等消费和取消补偿

共同裁决。Redis 和 localStorage 都不保存最终库存、价格、权益或订单成功事实。

## 3. 权限纵深

权限不是只在前端隐藏按钮。真实链路保留三道边界：

1. Storefront 只装配顾客旅程，不包含管理工作区；
2. Gateway 对 `/admin/**` 先执行角色门禁；
3. Catalog、Inventory、Marketing 各自再次执行服务内 RBAC。

`verify-m4-authoritative-checkout.ps1` 现会在授予夹具管理权限之前，使用同一枚真实
CUSTOMER JWT 对三个管理端入口分别执行 Gateway 与服务直连探测，共 6 次；每次都
必须返回 HTTP 403 且业务码为 `FORBIDDEN`。探测发送空请求体，即使安全配置意外
放行也只会进入参数校验，不会创建管理事实。

人工浏览器夹具为了创建商品、库存和权益，随后会给同一临时账号增加 ADMIN 并重新
登录，因此它只承担页面/F12/响应式证据，不被拿来冒充“纯顾客角色隔离”证据。纯
CUSTOMER 反证由授予前 JWT 的真实脚本完成，服务内 MockMvc 集成测试再独立覆盖。

## 4. 三层证据

| 边界 | 代码证明 | 自动化测试 | 真实运行证据 |
| --- | --- | --- | --- |
| 所有者隔离 | owner/token/access revision；pending v2 按 owner 分区 | checkout model 覆盖 A 慢响应晚于 B、同 owner token 更新和旧 pending 迁移 | 真实大整数 owner 通过 Gateway 读取自己的购物车、地址和权益；临时事实清理为 0 |
| 草稿迟到响应 | calculation revision 与 draft fingerprint 双校验 | 单测覆盖地址/权益/购物车改变后旧复核响应到达 | F12 记录快照试算 ¥378 和权威试算 ¥398 两份不同请求体，页面只保留后一裁决 |
| 并发提交 | 活动提交 Promise 合并；稳定幂等键和固定请求 | 单测断言并发调用只产生一个 Trade POST；Mock E2E 覆盖提交与恢复 | 真实脚本同键恢复与重试均返回同一订单，Trade 最终只有 `1|1|1` 一条待支付事实 |
| 价格、库存、权益 | 三域客户端逐项复核，60 秒有效期 | Storefront 单测与 Checkout Mock E2E；后端领域测试 | 真实 Catalog 189→199、Inventory available 5→3、Marketing 权益 AVAILABLE→LOCKED；取消后库存 5/0、权益 AVAILABLE |
| 权限纵深 | Gateway 及三个服务 SecurityConfig 分别声明管理角色 | Catalog、Inventory、Marketing 集成测试均断言 CUSTOMER→admin 为 403；Gateway 独立角色测试 | 真实 CUSTOMER JWT 经 Gateway 与直连三服务共 6 次均为 `403/FORBIDDEN` |
| 业务 ID 精度 | foundation 将业务 ID 定义为十进制字符串 | model、E2E 和类型门禁覆盖字符串 ID | 真实 user/product/SKU 均大于 `Number.MAX_SAFE_INTEGER`，浏览器请求与 MySQL 事实一致 |
| 页面可信度 | Workspace 只展示已确认状态，结果未知持久可见 | 13 个 Mock E2E 中 Checkout 覆盖价格变化、库存不足与复核 | 内置浏览器桌面/移动操作通过；页面 warning/error 为 0，不以日志代替网络与数据库证据 |

## 5. 浏览器与 F12 证据

真实运行只串行启动本闭环需要的资源：

```text
7 个 core 容器
Gateway + Identity + Catalog + Inventory + Trade + Marketing
Storefront Vite
```

Payment、Fulfillment、Chat、Analytics、观测栈和其他服务均未启动。每个 JVM 使用
`-Xmx256m`，同一时刻只运行一个真实浏览器闭环。

专用 Playwright 从浏览器侧监听 request/response/failed request/page error 和
console，而不是解析服务日志。1 个真实 Checkout 用例通过，捕获：

```text
GET  Trade cart                 200
GET  Identity addresses        200
GET  Marketing benefits        200
POST Marketing pricing preview 200 × 2
GET  Catalog product           200
GET  Inventory stock           200
failed request                 0
page error                     0
console error                  0
```

390×844 移动视口 `clientWidth/scrollWidth/bodyScrollWidth` 均为 390；桌面和移动
axe serious/critical 均为 0。Mock E2E 首次发现结算权威提示文字对比度为 4.28:1，
本批将其改为主题强强调色，复测通过；这项缺陷来自浏览器可访问性扫描，不是日志。

内置浏览器又独立执行正常用户路径：打开 `/checkout`、查看快照 ¥378、触发权威
复核、确认当前价格 ¥199、可用库存 3 和金额 ¥398，并在移动视口检查无水平溢出；
页面 console warning/error 为 0。浏览器客户端自身一次 Statsig 外联超时属于
Codex/代理工具链噪声，页面请求和业务响应均成功，未归因给 PlainJournal。

证据位于：

- `backend/.run/frontend-checkout-sixth-20260730/real-browser-evidence.json`
- `backend/.run/frontend-checkout-sixth-20260730/authoritative-checkout-rerun.out.log`

## 6. 自动化门禁

本批最终结果：

```text
分层规则       10 passed，扫描 50 个分层文件 / 179 条相对导入
Checkout model  9 passed
Foundation     42 Vitest
Storefront     80 Vitest
Admin          12 Vitest
合计          134 Vitest
Mock E2E       13 Playwright，1 worker
Real Checkout   1 Gateway E2E
typecheck      Foundation / Storefront / Admin passed
build          Storefront / Admin passed
axe            serious / critical = 0
```

生产构建：

```text
Storefront CSS 53.04 kB / gzip 8.63 kB
Storefront JS  284.90 kB / gzip 88.15 kB
Admin CSS      19.43 kB / gzip 3.91 kB
Admin JS      192.00 kB / gzip 61.89 kB
```

## 7. 清理与单机边界

真实权威脚本完成订单取消和跨域恢复后，临时 Identity、Catalog、Inventory、Trade、
Marketing 事实复查均为 0。随后只停止本批核验过命令行的 6 个 JVM 和 1 个 Vite
进程，精确删除已经停止的 Trade 实例遗留的单一 distributed ID lease；Compose
只执行 `stop`，保留数据卷，最后关闭本批启动的 Docker Desktop。

收口结果：

```text
批次业务端口监听             0
PlainJournal Java/Vite 进程  0
临时业务事实                 0
临时 worker lease            0
Docker Engine                unavailable
TCP 动态端口                 191 / 16384（1.2%）
UDP 动态端口                  12 / 16384（0.1%）
近 15 分钟 4231/4266         0
```

Codex 内置浏览器运行时的 Node 进程以项目目录为 working directory，但可执行文件和
脚本均属于 Codex 自身；它不是 Vite，也不能因为命令行含项目路径就被强杀。

## 8. 下一边界

Checkout 已成为边界明确的积木。下一前端切片可以审查订单/Payment 工作区，但必须
先画清 Trade、Payment、Fulfillment、After-sale 的状态所有权和结果未知恢复，再
决定拆分粒度；不得为了目录对称把一个状态机复制到多个 store。

M9 继续冻结，等待用户完成复审并单独确认准入。
