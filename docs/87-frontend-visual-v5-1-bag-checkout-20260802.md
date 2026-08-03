# 前端视觉 V5.1：购物袋与结算收口

> 完成日期：2026-08-02  
> 状态：V5.1 已完成；V5 整体仍在进行中  
> 范围：游客购物袋、账户购物车、登录合并、结算草稿、Marketing 试算、三域权威
> 核对、库存不足与订单结果未知恢复  
> 硬边界：未修改 API、DTO、store 事务语义、幂等键、状态机、权限或所有者域裁决权

## 1. 本批结论

V5.1 将购物袋和结算从旧式分区面板迁移为连续事实旅程：

```text
当前设备商品 / Trade 账户商品
  -> 明确分离与金额摘要
  -> 地址和权益选择
  -> Trade 商品快照与 Marketing 试算
  -> Catalog / Inventory / Marketing 权威核对
  -> Trade 下单或原请求恢复
```

设备游客袋与账户购物车继续是两个实体。登录后合并响应未知时，设备商品、稳定合并键
和固定快照全部保留；页面明确说明设备商品不计入账户小计。结算侧仍先展示 Trade
快照，再读取 Catalog 当前 SKU、Inventory 可用库存与 Marketing 当前试算。库存不足
只阻断提交，不伪造失败或成功；下单响应未知时只显示 `unknown`，保留原请求键和固定
载荷，允许查询或原键安全重试。

V5.1 只完成购物袋与结算。订单/Payment/Fulfillment、售后/寄回/退款尚未完成，不把
本报告写成整个 V5 收口。

## 2. 代码证明

### 2.1 购物袋

`BagPage.vue` 使用 `PjPageContainer`、`PjSurface`、`PjStatusNotice` 和 `PjButton`
组成页面。账户商品小计只来自 `accountCart.selectedSubtotal`；设备商品独立显示在
结果待确认提示中，文案明确“不计入上方账户小计”。

`AccountCartItemRow.vue` 与 `GuestBagItemRow.vue` 继续由各自 entity 持有交互。删除
确认色使用语义危险令牌，但不再通过 `!important` 越权覆盖 primitive。游客行保留
真实商品图片；账户行在现有 Trade 快照没有图片字段时使用克制的文字占位，不为视觉
效果修改 DTO。

账户 PUT/DELETE 的网络、超时、非法响应、5xx 和响应身份不匹配继续进入 `unknown`；
guest merge 继续复用原键与原快照。页面没有新增另一套购物车状态机。

### 2.2 结算

`CheckoutPage.vue` 只装配会话访问上下文与订单详情导航。
`CheckoutWorkspace.vue` 继续通过 checkout feature 串联：

1. Identity 地址；
2. Trade 已选购物车快照；
3. Marketing 可用权益与无副作用试算；
4. Catalog 当前商品和 SKU；
5. Inventory 当前可用库存；
6. Marketing 基于当前价格的再次试算；
7. Trade 稳定幂等下单与按键恢复。

页面用一条阅读顺序表达这些事实，没有按服务数量制造独立大卡。权威核对完成且库存
充足时使用 success；库存不足时使用 warning；订单结果未知使用 unknown。结果未知
区域同时保留“查询订单结果”和“使用原请求安全重试”，不生成新键。

### 2.3 共享组件与夹具

`PjSurface` 增加自动化断言，证明 `aria-labelledby` 会透传到选择的根元素；
`PjButton` 原有测试继续证明 loading 时禁用且可访问名称不丢失。

浏览器夹具增加购物车显式重置入口，使 V5 用例不依赖 M4 用例是否曾修改账户购物车。
该入口只服务前端契约测试，不参与生产构建，也不替代 Trade MySQL 事实。

## 3. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实运行证据 |
| --- | --- | --- | --- |
| 设备与账户不混算 | 两套 store、两套行组件；账户摘要只读 account-cart | `BagPage.test.ts` 断言 ¥89 账户小计不叠加 ¥378 设备商品；store 测试继续覆盖 owner 隔离 | V5 Chrome 夹具显示设备 pending 与账户摘要分离；真实 Gateway/MySQL owner 隔离沿用第 75 号封存证据 |
| 合并结果未知 | session 保留原 key/body/pending，成功后才扣设备袋 | session、guest-bag、BagPage 与 V5 E2E 断言两次 key/body 完全一致 | V5 浏览器实际丢弃第一次响应后保持 unknown，再以原键收敛；真实 Trade 同键只合并一次沿用第 75 号证据 |
| 三域权威核对 | checkout 依次读取 Catalog、Inventory、Marketing，并校验草稿指纹 | checkout store、Workspace 与 V5 E2E 捕获五类请求和最终 success | 内置浏览器显示实时单价 ¥189、可用 18 件并启用提交；真实价格变化、库存与权益裁决沿用第 76 号证据 |
| 库存不足 | `authorityReady` 只在每行 available ≥ quantity 时成立 | store、Workspace 与 V5 E2E 均断言 warning、按钮禁用、Trade POST 为 0 | Chrome 运行夹具在 available=1 时无法提交；真实 Inventory MySQL 裁决沿用第 76 号证据 |
| 订单结果未知 | owner-scoped pending、稳定 `order:{uuid}`、固定请求、原键查询/重试 | checkout store、Workspace 与 V5 E2E 断言 unknown、固定存储和两次同键 POST | Chrome 运行夹具丢弃 Trade 响应后保持 unknown；真实同键恢复为同一订单沿用第 76 号证据 |
| 权限与裁决权 | 页面只传访问上下文，未引入管理 API 或本地最终事实 | 16 条分层规则、既有权限和领域测试继续通过 | 本批未改权限或接口；Gateway/服务内 403 与真实 owner 事实引用 M0–M8 封存证据 |

本批没有启动完整中间件，也没有用 Mock 声称重新证明 MySQL、RocketMQ 或权限。原因是
视觉切片未改变 API、请求顺序、状态机或幂等协议；真实业务证据引用已经封存并通过
M0–M8 总审查的第 75、76 与 69 号文档。若后续 V5 改动任何业务判断，必须重新运行
对应真实链，不能继续引用历史证据。

## 4. 自动化门禁

针对性验证：

```text
高风险 store               35 / 35
V5.1 页面与 Workspace       4 / 4
UI primitives               5 / 5
V5.1 专项 Playwright         3 / 3
```

最终 `pnpm check`：

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 5 / 5 |
| Foundation | 42 / 42 |
| UI primitives | 5 / 5 |
| Storefront | 137 / 137 |
| Admin | 12 / 12 |
| 前端单元/契约测试合计 | 201 / 201 |
| 分层规则 | 16 / 16 |
| 分层文件 / 相对导入 | 106 / 221 |
| Playwright 全量 Mock E2E | 26 / 26 |
| V5.1 专项 Playwright | 3 / 3 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 88.18 kB / gzip 13.57 kB
Storefront JS  328.35 kB / gzip 101.69 kB
Admin CSS      28.61 kB / gzip 5.51 kB
Admin JS      196.19 kB / gzip 63.46 kB
```

## 5. 专项浏览器取证

V5 Playwright 只启动 Mock API 18090 与 Storefront 18200，单 worker 串行执行。三条
用例覆盖：

- 390px 游客购物袋的商品图片、金额与“不锁库存”提示；
- guest merge 服务端已处理但浏览器丢失响应，页面保持 unknown；
- 320px 下设备 pending、账户摘要和原键恢复无横向溢出；
- 桌面结算的地址、权益、商品快照和金额试算；
- Catalog、Inventory、Marketing 权威请求与可用库存；
- 库存不足 warning 和提交禁用；
- Trade 创建响应丢失后的 unknown、owner-scoped pending 与两次同键重试；
- axe serious/critical 为 0；非故障注入场景 Console warning/error 为 0。

内置浏览器另行执行正常用户路径：

- 桌面购物袋显示账户数量 3、账户小计 ¥567.00；
- 320px 传统滚动条环境为 `clientWidth=305`、`scrollWidth=305`；
- 320px 结算完成三域核对后显示实时单价 ¥189.00、可用 18 件，提交按钮启用；
- 桌面结算为两列连续旅程，`clientWidth=scrollWidth=1265`；
- 青荷与素白均复验，素白页面表面令牌为 `#f7f7f5`，结果未知语义令牌仍存在；
- 浏览器 Console warning/error 为 0。

从 320px 切回桌面时，内置浏览器截图表面曾一次未同步，但 DOM 已报告 1265px；
重新加载后截图恢复且页面宽度、网格和 Console 均正常。该现象没有在 Playwright、
DOM 布局或重载后复现，因此不归因给 PlainJournal。

## 6. 单机与清理

本批没有启动 Docker、Java、Redis、Nacos、RocketMQ 或其他完整中间件。专项
Playwright、完整前端门禁和内置浏览器严格串行；人工验收只启动两个 Node 进程。

验收结束前按监听端口和命令行双条件确认并停止：

```text
18090 -> node e2e/mock-api.mjs
18200 -> node Vite --port 18200
```

最终 18090/18200 无监听残留，临时启动脚本已删除；未结束 Codex 内部 Node，也未修改
机器级 Node、代理、网卡、Docker 数据或环境变量。

## 7. 下一坐标

V5.2 只处理：

1. 订单列表的信息层级与分页；
2. 订单详情中 Trade、Payment、Fulfillment 的连续事实旅程；
3. 支付处理中、结果未知与 `PAYMENT_EXCEPTION`；
4. 履约建立、物流时间线、确认收货与评价资格入口。

售后申请、寄回、仓库验收与退款留给后续 V5 切片。V5.2 开始前仍先审查现有
OrderDetail、Payment、Fulfillment 组件和测试，不一次性混入售后状态机。
