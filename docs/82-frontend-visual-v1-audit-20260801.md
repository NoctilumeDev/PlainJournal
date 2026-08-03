# 素简记前端视觉重构 V1 审计与冻结报告

> 审计日期：2026-08-01 至 2026-08-02（北京时间）  
> 当前状态：V1 审计与支付 P0 三层验收均已完成；视觉实现尚未开始  
> 下一坐标：由用户确认进入 V2 设计令牌、基础 primitives 与全局壳层  
> 项目边界：当前仓库止于自营 B2C v1.0，不进入多商户与 Go 平台化改造

> 2026-08-02 补充：V1-001、V1-002 已完成代码、175 个 Vitest、15 个
> Playwright、完整前端门禁和真实浏览器/真实中间件验收，详见
> [V1 支付恢复 P0 修复与三层验收](83-frontend-visual-v1-payment-recovery-20260802.md)。

## 1. 结论

V1 已完成源码、样式资产、全部顾客路由、代表管理路由、桌面与移动视口的只读审计，
并冻结首页、商品详情、订单详情的现状基线。本批没有修改产品视觉、API、状态机、权限
或业务判断。

当前视觉的主要问题不是颜色失控，而是全局样式所有权过宽、页面信息层级过多、长页
节奏稀散、技术域文案直接暴露，以及管理端响应式和演示夹具仍有缺口。青荷色彩已经
形成可用基础，但还没有沉淀为真正可复用的 primitives。

审计还通过真实故障注入发现一项资金边界 P0：支付创建结果未知后，页面的忙碌状态
可能永久不归零。该问题已取得代码、自动化失败断言和浏览器运行三层证据。它不是视觉
问题，不能借视觉重构掩盖。2026-08-02 已通过独立缺陷切片修复，并重新完成代码、
自动化和真实运行三层门禁。

## 2. 审计边界与方法

本次只启动一个 Mock API 和一个 Vite，顾客端与管理端串行互斥，不启动 Docker 或全套
中间件。每个路由均通过真实浏览器导航、DOM/F12 检查与全页截图采样；不能从日志
“没有报错”反推页面正确。

| 范围 | 结果 |
| --- | ---: |
| 顾客端路由 | 19 / 19 |
| 顾客端桌面 / 移动基线 | 19 / 19 |
| 顾客端额外支付状态截图 | 2（处理中、结果未知） |
| 管理端代表桌面路由 | 9 |
| 管理端代表移动路由 | 3 |
| 截图总数 | 52 |
| 顾客端基线 DOM 样本 | 38 |
| 审计证据文件 | 61，约 2.96 MiB |

证据根目录：

```text
backend/.run/frontend-visual-v1-audit-20260801/
```

主要机器可读证据：

```text
storefront-baseline.json
admin-baseline.json
static-audit.json
payment-processing-metrics.json
payment-busy-regression-failing-test.log
```

该目录是本地运行证据，不作为产品源码。GitHub 发布阶段应从稳定实现重新生成最终展示
截图，不能把 V1 的问题截图冒充成完成效果。

## 3. 运行基线

### 3.1 顾客端

19 个真实路由分别在 1280×900 和 390×844 下采样：

```text
首页、商品列表、商品详情、搜索、全局索引、购物袋、登录、注册、账户、地址、结算、
订单列表、订单详情、售后列表、售后详情、权益、客服列表、客服详情、404
```

38 个基线样本全部满足：

- 单一 `main`；
- 无横向溢出；
- 页面控制台 warning/error 为 0；
- 路由标题和主标题可读取。

这只能证明结构底线成立，不能证明视觉质量已达标。高密度页面长度已经暴露明显节奏
问题：

| 页面 | 桌面高度 | 移动高度 |
| --- | ---: | ---: |
| 已完成订单详情 | 4406 px | 5759 px |
| 售后详情 | 2937 px | 4081 px |
| 商品详情 | 2371 px | 2748 px |
| 首页 | 2468 px | 2743 px |
| 结算 | 1497 px | 2675 px |

### 3.2 管理端

桌面采样工作区、商品、库存、履约、售后、营销、客服、评价和治理九个入口；移动端重点
采样工作区、履约和治理三种信息密度。

- 所有样本均只有一个 `main`，页面控制台 warning/error 为 0；
- 工作区首页可见 `GET /api/v1/analytics/overview is not mocked`，说明演示夹具没有覆盖
  真实首页能力；
- 390px 履约页 `clientWidth=375`、`scrollWidth=438`，存在 63px 横向溢出；
- 溢出链从横向导航的 min-content 宽度传到 `.admin-layout`、`main` 和 `.admin-page`，
  不是某个长业务编号单独造成；
- 桌面端若干管理页只使用左侧窄栏，大面积可用宽度没有形成清楚的信息密度结构。

因此“控制台 0 错误”与“页面正确”不是同一个结论，V1 特意保留了可见告警和布局测量。

## 4. 样式与资产基线

| 项目 | 当前事实 |
| --- | ---: |
| Vue 文件 | 50 |
| 带 `<style>` / scoped style | 18 / 17 |
| 独立 CSS 文件 / 总行数 | 6 / 2690 |
| `storefront.css` | 1490 行 |
| `admin.css` | 960 行 |
| 顾客端应用 CSS 类 | 113 |
| 管理端应用 CSS 类 | 66 |
| 顾客端 / 管理端媒体查询 | 7 / 4（不含设计系统与 scoped style） |
| 行内 `style=` | 0 |
| `!important` | 管理端 1 处 |
| 商品与履约图片 | 3 张，合计约 5.59 MiB |

两处顾客端“未使用”类是动态 tone 生成的误报：
`order-status-badge--active` 与 `order-status-badge--warning` 在状态模型中真实可达。
管理端 `.status-label--bounded` 没有找到动态或静态消费者，是 V2/V6 清理候选，但必须
先由构建、组件测试和真实路由共同证明后才能删除。

源码里的四个颜色字面量全部属于主题模型与其测试中的浏览器 `theme-color` 值，不是
页面绕过令牌写色。当前真正的结构债务是：

- `storefront.css` 同时持有壳层、商品、结算、订单、售后、账户和支持页面规则；
- `admin.css` 用一个文件持有全部管理领域与响应式；
- 页面通过 `.primary-action`、`.checkout-section`、`.payment-state` 等全局类隐式耦合；
- 断点存在 rem 与 px 混用，页面局部规则尚未统一为命名语义；
- 图片没有 WebP/AVIF 和响应式尺寸证据。

## 5. P0：支付结果未知后忙碌状态不归零（已关闭）

### 5.1 代码证明

`paymentStore.ts` 的创建流程先保存：

```text
requestRevision = ++submissionRevision
creatingOrderNo = orderNo
```

创建发生不确定失败后，又调用 `recoverPendingSubmissionForAccess()`；恢复查询会再次执行
`++submissionRevision`。外层 `finally` 只在旧 `requestRevision === submissionRevision`
时清空 `creatingOrderNo`，因此恢复查询完成后条件必然不成立，忙碌状态被永久保留。

相关位置：

```text
frontend/storefront-web/src/features/order-payment/model/paymentStore.ts:352
frontend/storefront-web/src/features/order-payment/model/paymentStore.ts:385
frontend/storefront-web/src/features/order-payment/model/paymentStore.ts:488
frontend/storefront-web/src/features/order-payment/model/paymentStore.ts:511
frontend/storefront-web/src/features/order-payment/model/paymentStore.ts:525
```

### 5.2 自动化证明

现有测试已经覆盖“POST 503 + 按原键查询 404 + 再次安全重试”，但只断言两次 POST 使用
同一个幂等键，没有断言忙碌状态归零。V1 临时加入：

```ts
expect(payments.creatingOrderNo).toBeNull();
expect(payments.resolvingSubmission).toBe(false);
```

定向执行 9 个测试得到 1 个失败：实际 `creatingOrderNo` 仍为订单号。失败原文保存在
`payment-busy-regression-failing-test.log`。临时断言随后已原样撤除，没有把故意失败的
测试留在工作树。

### 5.3 真实浏览器证明

在顾客真实订单页创建支付单时，V1 只终止已核对命令行的 Mock API 监听子进程，制造
创建响应不确定；恢复 API 后继续观察页面：

- 原支付键仍保留，取消动作正确关闭；
- 两个按钮持续禁用并显示“正在确认”；
- 等待超过接口 8 秒超时预算及恢复查询时间后仍未归零；
- 页面没有控制台错误，证明不能依赖日志发现该缺陷。

截图：

```text
storefront-desktop/order-detail-payment-unknown.png
```

随后由 Mock API 建立 `PROCESSING` 权威事实并重新加载订单页，页面正确显示“不提前
成功”，但旧 pending 区块仍同时出现空说明和“原键安全重试”。截图：

```text
storefront-desktop/order-detail-payment-processing.png
```

这说明修复时还必须验证：读取到同订单 Payment 权威事实后，设备 pending 记录应如何
收敛，不能只把按钮解锁。

### 5.4 2026-08-02 修复验收

- 创建请求与 pending 恢复查询改用独立 revision，内层查询不再阻止创建流程
  `finally` 清理忙碌状态；
- 读取同订单 Payment 权威事实后清除设备 pending；不同订单 pending 不误删；
- Payment Store 11 个定向测试通过；
- 完整前端门禁通过 15 条分层规则、175 个 Vitest、15 个 Playwright、类型检查和
  两端生产构建；
- 真实浏览器验证上游支付创建 HTTP 200 后响应丢失，页面恢复唯一
  `PROCESSING` Payment、取消入口关闭且旧 pending 重试区消失；
- 签名回调后 Payment、Trade、Inventory、Fulfillment 全部收敛，相关消费失败和
  最终夹具残留均为 0。

完整证据见
[V1 支付恢复 P0 修复与三层验收](83-frontend-visual-v1-payment-recovery-20260802.md)。

## 6. 问题矩阵

| ID | 等级 | 页面 / 类型 | 事实与影响 | 建议归属 | 业务风险 |
| --- | --- | --- | --- | --- | --- |
| V1-001 | P0 | 订单详情 / 恢复 | 结果未知后 `creatingOrderNo` 永久不归零 | 独立业务缺陷切片 | 资金、重复动作 |
| V1-002 | P0 | 订单详情 / 收敛 | 已读到 PROCESSING 权威事实时仍显示旧 pending 安全重试区 | 独立业务缺陷切片 | 资金、幂等理解 |
| V1-003 | P1 | 订单详情 / 层级 | 未知状态有标题、正文、双按钮和反馈三次重复 | domain component | 误操作 |
| V1-004 | P1 | 管理履约 / 响应式 | 390px 实测横向溢出 63px，导航 min-content 撑开主布局 | app shell + page composition | 可用性 |
| V1-005 | P1 | 管理首页 / 夹具 | 运营统计接口未 Mock，首页长期呈现未确认告警 | 演示夹具 | 证据可信度 |
| V1-006 | P1 | 顾客交易链 / 文案 | Trade、Payment、Fulfillment 等服务所有权成为主视觉标题 | domain component + copy | 认知负担 |
| V1-007 | P1 | 订单与售后 / 节奏 | 移动订单详情 5759px，区域重复边界与说明过多 | page composition | 关键动作发现 |
| V1-008 | P1 | 桌面全站 / 布局 | 商品与部分管理页内容窄、右侧长期空置，阅读宽度缺少统一语法 | app shell + layout primitive | 信息效率 |
| V1-009 | P1 | 全站 / 所有权 | 两个超大 CSS 文件持有跨页面规则，组件靠全局类隐式耦合 | tokens → primitives | 维护风险 |
| V1-010 | P1 | 售后异常 / 证据 | 浏览器夹具没有 `NEEDS_ATTENTION`，不能验收异常视觉语义 | 测试夹具 + domain component | 资金、售后 |
| V1-011 | P2 | 全站 / 文案 | 顾客 Vue 中 `.eyebrow` 使用 76 次，几乎退化为固定装饰 | copy + primitive | 视觉噪声 |
| V1-012 | P2 | 全站 / 断点 | 32/48/64rem 外仍有局部 rem/px 断点 | design-system | 回归风险 |
| V1-013 | P2 | 商品 / 资产 | 三张图片约 5.59 MiB，无响应式格式和体积门禁 | media primitive | 首屏性能 |
| V1-014 | P2 | 管理端 / 死样式 | `.status-label--bounded` 暂无消费者 | admin primitive | 维护债务 |

矩阵中的 P0/P1 不等于允许在 V2 中顺手改业务。V1-001、V1-002 必须另立修复与三层
验证；其余项目分别归入 V2–V6，不再让页面各自造解法。

## 7. 三个原型的冻结样本

### 7.1 首页

冻结证据：

```text
storefront-desktop/home.png
storefront-mobile/home.png
```

保留方向：青荷浅表面、商品图片、克制主行动。需要重构：首屏高度与大空区、段落节奏、
导航密度以及页面下半部的重复“标准说明”。

### 7.2 商品详情

冻结证据：

```text
storefront-desktop/product-detail.png
storefront-mobile/product-detail.png
```

保留方向：商品图比例、规格事实、价格与加购不伪造库存。需要重构：桌面首屏宽度利用、
信息分组、纵向空白和评价区域的卡片化倾向。

### 7.3 订单详情

冻结证据：

```text
storefront-desktop/order-detail-completed.png
storefront-mobile/order-detail-completed.png
storefront-desktop/order-detail-payment-processing.png
storefront-desktop/order-detail-payment-unknown.png
```

已稳定复现完成、处理中和结果未知三类事实。`NEEDS_ATTENTION` 目前只有源码展示逻辑，
没有浏览器夹具；它是 V3 验收前的强制补证项，不能用静态图片或改前端内存状态代替。

## 8. V2 候选，不等于已实施

V1 冻结以下候选层级，具体 API 必须由三个原型共同验证后再确定：

```text
tokens
  canvas / surface / text / line / action / focus / media / status / motion

primitives
  PageContainer / Surface / Button / TextAction / Field / StatusNotice
  ActionGroup / DefinitionList / EmptyState / ResponsiveMedia

domain components
  ProductMedia / ProductPrice / OrderStatus / PaymentFact
  FulfillmentTimeline / RefundFact / RiskRecoveryNotice

page composition
  DiscoveryFlow / PurchaseFlow / OrderJourney / GovernanceWorkspace
```

最先迁移的仍是全局壳层和基础控件，不先创建万能业务卡片。页面不得重新定义按钮、
字段、焦点、禁用、错误或状态颜色。

## 9. V1 退出与 V2 准入

V1 的截图、问题归属、样式资产和三个原型现状已经冻结；V1-001、V1-002 已完成独立
修复、自动化断言和真实浏览器/真实服务链验收，V1 正式完成。

管理端统计夹具、移动溢出、`NEEDS_ATTENTION` 浏览器证据和其他 P1/P2 不被伪装为
已解决，继续按问题矩阵进入对应 V3/V6 切片。它们不允许在 V2 中顺手扩张业务范围。

V2 只建立令牌、primitives 和全局壳层；不批量重画页面。V3 再用首页、商品详情和订单
详情验证整套视觉语法，确认后才向 V4–V6 扩散。
