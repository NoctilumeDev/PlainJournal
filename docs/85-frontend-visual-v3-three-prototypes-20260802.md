# 前端视觉 V3：三个真实原型收口

> 完成日期：2026-08-02  
> 状态：V3 已完成，停在 V4 之前等待用户确认  
> 范围：首页、商品详情、订单详情，以及订单内 Payment、Fulfillment、评价展示  
> 硬边界：未修改 API、DTO、后端状态机、权限、幂等键或跨服务裁决权

## 1. 本批结论

V3 已在三个现有真实路由上验证同一套视觉语言：

```text
首页：建立品牌、商品与浏览节奏
  ↓
商品详情：连续呈现媒体、规格、价格、购买动作与评价
  ↓
订单详情：按用户旅程串联订单、支付、履约、评价与售后事实
```

三个页面都直接消费既有业务状态和 `@plain-journal/ui`，没有另建静态演示页。青荷仍
为默认主题，素白使用同一布局、组件和风险语义。首页不再依赖广告式大 Banner；商品
详情不再把规格、购买和评价割裂为重卡片；订单详情不再把 Trade、Payment 与
Fulfillment 的服务边界直接做成主视觉隔墙。

V3 退出门禁已满足：

- 首页、商品详情和订单详情均只有一个 `h1` 和一个主内容区域；
- 320px、390px 与桌面代表宽度没有根页面横向溢出；
- 商品图和履约插图均完成真实解码；
- 商品加购反馈、购物袋计数和主题持久化正常；
- 待支付、已完成和 `PAYMENT_EXCEPTION` 三种订单事实没有混淆；
- 支付异常不会提前展示履约正在建立，也不会请求 Fulfillment；
- Playwright 请求/响应取证、页面错误和浏览器 Console 门禁通过；
- axe serious / critical 为 0。

## 2. 代码证明

### 2.1 首页

`frontend/storefront-web/src/pages/home/HomePage.vue` 收紧 Hero 高度和行动数量，以真实
商品图、用途入口和精选商品构成浏览主线。页面保留 Catalog 真实路由和商品字段，不
增加促销倒计时、折扣徽章或伪造运营事实。

`frontend/storefront-web/src/styles/storefront.css` 将首页从广告落地页式堆叠调整为
更弱边界、更稳定留白和更清楚的商品图片比例。页面仍使用语义令牌，没有引入页面
硬编码色值或第二套按钮语法。

### 2.2 商品详情

`frontend/storefront-web/src/views/ProductDetailView.vue` 已迁移到
`PjPageContainer`、`PjSurface`、`PjButton`、`PjStatusNotice` 和
`PjActionGroup`。商品媒体、规格、价格和主要购买动作形成一条连续阅读路径，原有
Catalog API、SKU 选择和游客购物袋逻辑保持不变。

`frontend/storefront-web/src/features/product-reviews/ui/ProductReviewsSection.vue`
把评价从卡片矩阵调整为连续内容列表，仍保留点赞、举报、登录返回地址和服务端评价
事实。

### 2.3 订单详情

`frontend/storefront-web/src/views/OrderDetailView.vue` 按以下顺序组合页面：

```text
当前订单事实与下一步
  -> 商品不可变快照
  -> 收货地址快照
  -> Payment 事实
  -> Fulfillment 事实（仅可能存在时）
  -> 评价与售后
  -> 金额快照与后续入口
```

支付、履约与评价仍由原 feature/entity 持有状态和动作；页面只负责旅程组合。支付
未知结果仍只有一个原幂等键恢复入口，取消与可能在途的支付继续失败关闭。

`frontend/storefront-web/src/features/order-fulfillment/ui/OrderFulfillmentSection.vue`
保留真实履约插图、物流位置、时间线、轨迹和确认收货状态机，但视觉上与订单事实形成
连续内容，不把 Fulfillment 服务名作为主标题。

## 3. 审查中发现并关闭的高风险事实错误

### 3.1 问题

后端 `PAYMENT_EXCEPTION` 表示支付与订单事实需要人工核对。该状态不会发布正常
`OrderPaid` 事实，也不能据此假定已经建立履约单。旧订单页面却把
`PAYMENT_EXCEPTION` 纳入“可能存在履约”的条件，可能显示“配送信息正在建立”，
把支付异常错误解释为正向履约正在推进。

### 3.2 修复

`OrderDetailView.vue` 的 `orderMayHaveFulfillment` 现在只包含：

- `PAID`
- `FULFILLING`
- `SHIPPED`
- `COMPLETED`

`PAYMENT_EXCEPTION` 只展示需要人工核对的订单事实和已经保存的 Payment 事实，不
渲染 `OrderFulfillmentSection`，也不请求 Fulfillment。

### 3.3 三层证据

| 层 | 证据 |
| --- | --- |
| 代码 | `orderMayHaveFulfillment` 排除 `PAYMENT_EXCEPTION`；下一步只指向 Payment 核对区 |
| 自动化 | `OrderDetailView.test.ts` 断言页面无履约区、无“配送信息正在建立”，请求只有 Trade 与 Payment |
| 真实运行 | V3 Playwright 捕获请求/响应并证明异常订单未发出 Fulfillment 请求；内置浏览器实际打开异常订单，只显示人工核对与 Payment 成功事实，页面无履约区、无控制台 warning/error |

这次修复只让前端展示与既有后端事实一致，没有改变后端事件、订单状态或履约创建
条件。Mock API 与浏览器证据不替代 M0–M8 已封存的真实 MySQL、RocketMQ 和所有者域
证据。

## 4. 自动化证据

环境：

- Node.js `D:\Node.js\current\node.exe`，24.14.0；
- pnpm 11.9.0；
- Windows PowerShell；
- Mock API、Storefront、Admin 与 Playwright 由统一脚本串行启动和停止。

最终结果：

| 门禁 | 结果 |
| --- | ---: |
| 设计令牌契约 | 3 / 3 |
| Foundation | 42 / 42 |
| UI primitives | 4 / 4 |
| Storefront | 122 / 122 |
| Admin | 12 / 12 |
| 前端单元/契约测试合计 | 183 / 183 |
| 分层规则 | 16 / 16 |
| Playwright 全量 Mock E2E | 20 / 20 |
| V3 专项 Playwright | 3 / 3 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

`frontend/tools/run-e2e.ps1` 现在统一串行执行：

```text
Mock API
  -> Storefront
  -> Admin
  -> Playwright
  -> 按已核对 PID 停止本批进程
```

这避免多个测试入口并行争用端口、Node 运行时和机器资源。V3 另提供
`playwright.v3.config.ts`，只运行三个原型用例，便于后续视觉迭代做最小回归。

## 5. 内置浏览器与 F12 证据

本批人工验收只启动 Mock API 18090 和 Storefront 18200，没有启动 Docker、完整
中间件或 Admin。检查结束后已停止两项进程，两个端口均无监听残留。

### 5.1 首页

- 单一 `h1`、单一 `main`；
- 两张商品图真实加载，页面无横向溢出；
- Hero 与商品区层级清楚，没有促销弹层或广告式行动堆叠；
- 青荷默认主题恢复正常；
- Console 只有 Vite debug，warning/error 为 0。

### 5.2 商品详情

- 商品主图真实解码，`naturalWidth = 1122`；
- 桌面为稳定双栏，媒体与购买区不互相覆盖；
- 点击“加入购物袋”后出现明确成功状态和购物袋计数；
- 提示继续声明“加入购物袋不锁定库存，结算时重新校验”；
- 从全局索引切换素白后，重新进入商品页仍保持 `data-pj-theme = subai`；
- 恢复青荷后 `data-pj-theme = qinghe`；
- 页面无横向溢出，Console warning/error 为 0。

### 5.3 订单详情

- 待支付：显示“库存已经预占”“尚未建立支付单”和唯一主要行动，不把订单创建
  解释为支付成功；
- 已完成：显示 Payment 成功、履约签收、位置、物流时间线、评价和售后入口，履约
  插图真实解码，`naturalWidth = 1672`；
- `PAYMENT_EXCEPTION`：订单级为“需要人工核对”，Payment 可显示已保存的成功事实，
  但没有履约标题，也没有“配送信息正在建立”；
- 三个页面的 `clientWidth` 与 `scrollWidth` 相等；
- Console warning/error 为 0。

移动端真实视口由 Playwright 在 320×800 和 390×844 下串行验证。内置浏览器当前
固定视口约为 1265×720，不用脚本伪造窗口尺寸冒充移动端证据。

## 6. 真实性与回退边界

V3 修改的是视觉组合与一个前端展示条件，没有修改真实业务契约。资金、库存、权益、
权限、消息投递和最终一致性的裁决仍引用 M0–M8 三层真实链证据。若后续 V4–V6 修改
请求顺序、幂等恢复、动作互斥或状态映射，必须重新运行对应真实链。

本批可回退单位是三个页面及其局部视觉消费者；不得回退业务数据库迁移、后端状态机
或用户现有未提交成果。禁止使用 `reset`、`checkout` 或 `clean`。

## 7. 下一坐标

V3 正式关闭。下一阶段是 V4 商品发现链：

1. 商品列表与分类；
2. 搜索与查询状态；
3. 全局索引；
4. 商品卡、商品网格与公开评价；
5. URL、分页、降级来源和刷新恢复。

V4 不在本批自动开始，等待用户确认。
