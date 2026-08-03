# 前端视觉 V5.3：售后与退款收口

> 完成日期：2026-08-02  
> 状态：V5.3 已完成；V5 交易与售后链整体完成  
> 范围：售后列表、售后详情、顾客寄回、仓库事实、退款状态与旧全局样式清理  
> 硬边界：未修改 API、DTO、状态机、幂等键、权限、所有者隔离或补偿命令

## 1. 本批结论

V5.3 没有把 Trade、Fulfillment 和 Payment 合并成一个前端状态机，而是保持代码
所有权分离，把顾客阅读顺序收束为一条逆向交易旅程：

```text
当前售后事实
  -> 当前处理方与下一步
  -> 寄回、仓库收货与验收
  -> 渠道退款
  -> 不可变退款商品快照
```

售后列表从可点击重卡片迁移为连续事实行。详情页使用共享 `PjPageContainer`、
`PjStatusNotice`、`PjButton`、`PjField`、`PjActionGroup` 和 `PjSurface`，不再依赖
旧 `order-card`、`payment-state`、`checkout-section` 或 `checkout-summary`。

## 2. 状态语义

| 权威事实 | 页面语义 | 顾客动作 |
| --- | --- | --- |
| 寄回返回 `RETURNING` 且运单匹配 | success | 等待仓库收货 |
| 寄回响应丢失且读回仍为 `WAIT_SHIPMENT` | unknown | 先查询，不更换运单号重复提交 |
| 取消返回 `CANCELED` | success | 无 |
| 取消响应丢失且 Trade 仍为 `APPLIED` | unknown | 查询 Trade 事实后再决定是否重试 |
| Refund `PROCESSING` | processing | 等待渠道明确结果 |
| Refund `SUCCESS` | refunded | 无 |
| Refund `FAILED` 或请求为 `NEEDS_ATTENTION` | attention | 顾客只读，等待平台治理 |

`NEEDS_ATTENTION` 没有被解释为退款成功、退款完成或顾客可操作失败。页面只说明平台会在
授权、幂等和审计边界内核对恢复；Payment 管理补偿按钮仍只存在于管理端治理工作区。

## 3. 代码证明

### 3.1 展示层

- `AfterSaleListView.vue` 使用连续 `after-sale-row`，按时间、订单、退款金额、当前
  处理方和下一步组织信息；
- `AfterSaleDetailView.vue` 只负责路由编号和访问上下文装配；
- `AfterSaleWorkspace.vue` 保留三个 entity store，只组合事实，不复制状态机；
- 普通字符串 feedback 改为 `{ tone, title, message }`，成功与未知不能共用外观；
- `AfterSaleProgress.vue` 使用正式语义令牌和 48/32rem 主断点，不再消费兼容颜色别名
  或 `order-status-badge`。

### 3.2 输入约束

专项浏览器首次运行发现 Chrome 以 Unicode Sets (`v`) 解析 HTML `pattern` 时，旧字符
类中的连字符会产生正则语法错误。承运商与运单约束已改为不在字符类中直接放置连字符
的表达式；第二次运行 Console warning/error 为 0。

### 3.3 测试夹具

Mock API 增加 `/__test__/fixtures/after-sale/reset`，只用于在串行浏览器测试之间恢复
售后、退货与退款初始事实，避免寄回动作污染后续用例。它不改变生产 API。

## 4. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实运行证据 |
| --- | --- | --- | --- |
| 寄回成功只接受 Fulfillment 明确事实 | store 只接受匹配 owner、退货单、售后单和运单的非 `WAIT_SHIPMENT` 事实；页面只在 `RETURNING` 显示 success | store 既有并发/迟到响应测试 + V5.3 组件测试 | Playwright 实际 POST shipment 200，随后 Trade/Fulfillment 均为 `RETURNING`，页面才显示成功 |
| 寄回结果未知不伪造成功 | `submissionUnknown` 与结构化 unknown feedback 独立于 success | 503 + owner read 仍为 `WAIT_SHIPMENT` 的组件测试 | Playwright POST 503 后再次 GET 仍为 `WAIT_SHIPMENT`，DOM 无 success，保留原运单提示 |
| 取消成功与未知分离 | Trade 返回 `CANCELED` 才建立 success；不确定异常先读回权威事实 | 组件测试分别覆盖 `CANCELED` 与 503 后仍 `APPLIED` | Playwright 捕获取消 POST 503 和第二次 Trade GET，页面保持 unknown 且无 success |
| 退款处理中不等于到账 | refund presentation 将 `PROCESSING` 映射为 processing，只有 `SUCCESS` 为 refunded | entity 与 workspace 测试断言 processing、无 refunded | Playwright 正常退款夹具显示 processing，仓库事实不会提前改变资金语义 |
| `NEEDS_ATTENTION` 只读治理 | request status 优先映射 attention；顾客组件没有管理 API 或命令组件依赖 | entity/workspace 测试断言 attention 且无补偿按钮 | Playwright 双主题均显示 attention，请求链只有顾客只读查询，无管理补偿请求 |
| owner/权限/补偿边界未变 | 页面继续只传 owner/token access；三个 stores 仍拒绝账户切换后的迟到响应 | 既有 owner、token、并发、幂等和分层测试全部通过 | 本批未改后端契约；真实 MySQL/RocketMQ/权限/补偿证据继续引用第 16、19、40、69 号文档 |

受控 HTTP 运行证明页面请求、DOM、错误恢复、响应式和无障碍，不替代真实数据库与消息
事实。本批没有触碰后端协议，因此服务端真实链继续沿用已经封存的 M0–M8 三层证据。

## 5. 自动化门禁

针对性验证：

```text
V5.3 售后 entity / workspace / list / progress   11 / 11
V5.3 专项 Playwright                              3 / 3
```

最终 `pnpm check`：

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 42 / 42 |
| UI primitives | 5 / 5 |
| Storefront | 147 / 147 |
| Admin | 12 / 12 |
| 前端单元/契约测试合计 | 212 / 212 |
| 分层规则 | 16 / 16 |
| 分层文件 / 相对导入 | 108 / 226 |
| Playwright 全量 Mock E2E | 31 / 31 |
| V5.3 专项 Playwright | 3 / 3 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 96.29 kB / gzip 13.90 kB
Storefront JS  331.74 kB / gzip 101.95 kB
Admin CSS      28.61 kB / gzip 5.51 kB
Admin JS      196.19 kB / gzip 63.46 kB
```

## 6. 浏览器与请求取证

专项 Playwright 单 worker 串行启动 Mock API 与 Storefront，实际验证：

- 售后列表为连续事实行，`order-card` 数量为 0；
- 寄回 POST 200 后才出现 success，Trade/Fulfillment 同步为 `RETURNING`；
- 寄回 POST 503 后读回仍为 `WAIT_SHIPMENT`，页面只显示 unknown；
- 取消 POST 503 后再次查询 Trade 仍为 `APPLIED`，页面不显示取消成功；
- `PROCESSING`、`NEEDS_ATTENTION`、refunded 三种状态 class 不混用；
- 顾客页面没有补偿按钮，也没有管理补偿请求；
- 青荷与素白语义一致；
- 320、390、1280px 根页面无横向溢出；
- axe serious/critical 为 0，非注入故障 Console warning/error 为 0。

完整回归第一次暴露旧 M4 断言对 `WAIT_SHIPMENT` 使用全局文本定位；新页面在进度区和
事实摘要各显示一次，Playwright 严格模式拒绝歧义选择。断言已收窄到
`.after-sale-boundary dd`，第二次完整回归 31/31 通过。这是测试定位修复，不是业务
状态修复。

内置 Codex 浏览器连接成功，但当前客户端对 `localhost` 和 `127.0.0.1` 直接返回
`ERR_BLOCKED_BY_CLIENT`。18000 与 18200 当时均有明确监听，随后已按启动 PID 关闭；
因此没有把该入口失败写成页面失败，也没有切换其他浏览器冒充内置浏览器证据。可见
页面、F12 请求和 Console 的本批运行证据来自同机 Chrome Playwright。

## 7. 旧样式清理

迁移后重新扫描 Vue 消费者，以下旧全局类已归零并从 `storefront.css` 删除：

- `checkout-section`；
- `checkout-summary`；
- `payment-state`；
- `order-card` 及其子类；
- `return-shipment-form`；
- `after-sale-card`；
- `after-sale-detail-link`；
- 旧全局 `payment-facts`、`order-detail-layout` 等已经由 scoped CSS 持有的重复规则。

`storefront.css` 当前为 619 行。`order-status-badge` 仍被 `BenefitCenterView.vue`
使用，因此本批保留；它应在 V6 账户与权益迁移时处理。

## 8. 单机边界与下一坐标

本批没有启动 Docker、Java、Redis、Nacos、RocketMQ 或完整中间件。完整检查、专项
浏览器和人工入口尝试严格串行；临时 Mock API 与 Storefront 均已关闭，18000、
18090、18200 无残留监听。

V5 已完成。下一阶段进入 V6，按顺序迁移：

1. 登录、注册与账户；
2. 地址与优惠权益；
3. 通知与 Chat；
4. 管理端导航、表格、治理命令和审计反馈。

V6 继续保持同一边界：视觉可以重构，权限、结果未知、补偿与所有者裁决权不能被
美学隐藏。
