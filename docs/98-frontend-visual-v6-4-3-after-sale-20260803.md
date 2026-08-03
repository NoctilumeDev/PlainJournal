# 前端视觉 V6.4.3：After-sale 管理审核工作区

> 完成日期：2026-08-03  
> 状态：V6.4.3 After-sale 切片已完成；V6.4.3 仍剩 Review、Chat 与管理首页  
> 范围：Trade 售后列表、整单退款快照、审核决定与结果未知恢复  
> 硬边界：未修改 Trade API、售后状态机、Outbox、退款金额、权限或跨服务推进关系

## 1. 本批核心结论

旧管理页把售后列表、筛选、请求、审核表单和错误解释全部放在一个 Vue 文件中，并把
所有异常压成普通错误。最危险的边界是：

```text
POST /api/v1/trade/admin/after-sales/{afterSaleNo}/review
```

该接口没有独立 `Idempotency-Key` 或命令 ID，但 Trade 权威 DTO 返回审核原因、状态、
批准时间和版本。后端在行锁事务中执行：

- 只有 `APPLIED` 可以首次审核；
- 审核通过进入 `WAIT_RETURN`，拒绝进入 `REJECTED`；
- 当前状态已经等于相同目标时直接返回已有事实，不追加第二次状态迁移；
- 审核原因、历史、状态和 Outbox 在同一本地事务提交。

因此前端恢复协议不能套用 Payment 的审计命令，也不能把 5xx 当成失败或成功，而是：

```text
冻结售后号 + 决定 + 原因
  -> 先按售后号读取 Trade 权威事实
  -> 相同决定、相同原因和合法状态路径：确认业务结果
  -> 仍为 APPLIED：只开放原载荷重试
  -> 其他状态或不同原因：拒绝归因，并禁止重试
```

Trade DTO 不公开审核命令 ID，因此页面只确认业务结果，不伪造精确命令身份。

## 2. 代码结构

迁移后依赖方向为：

```text
Foundation Trade API
          ↓
entities/admin-after-sale
  - DTO runtime validation
  - operator/token access revision
  - status filter and newest response wins
  - pending review persistence
  - authority-first recovery
  - exact frozen-payload retry
          ↓
AfterSaleWorkspaceView
  - continuous reverse-transaction facts
  - immutable refund item allocation
  - review fields / actions / semantic notices
```

页面只通过 `entities/admin-after-sale/index.ts` 使用 entity，不再直接装配 API。entity
显式接收 `authorized + operatorId + accessToken`，不依赖旧 session store。员工或
token 切换后，旧列表或审核响应只能静默作废；未确认审核按管理员 ID 保存在本机，
不会进入另一个员工上下文。

## 3. 列表与合同校验

管理列表完整覆盖 Trade 的九种售后状态：

```text
APPLIED
WAIT_RETURN
RETURNING
RECEIVED
REFUNDING
REFUND_FAILED
COMPLETED
REJECTED
CANCELED
```

旧页面遗漏的 `RECEIVED` 和 `CANCELED` 已补回筛选。entity 对列表与详情执行运行时
校验：

- 售后号和订单号必须符合业务号格式；
- 顾客、SKU 等 64 位 ID 必须保持十进制字符串；
- 状态必须属于后端真实枚举；
- 金额必须非负且最多两位小数；
- 商品行号不能重复；
- 创建、更新、批准和完成时间必须是合法时间；
- 带状态筛选的响应不能混入其他状态；
- 同一列表不能出现重复售后号。

列表刷新遇到 503、超时或非法 DTO 时保留上一次已知 Trade 事实，不显示伪造空列表。

## 4. 审核恢复协议

### 4.1 明确成功

首次 POST 只有同时满足以下条件才显示成功：

- 返回售后号等于当前售后号；
- 审核通过返回 `WAIT_RETURN`，拒绝返回 `REJECTED`；
- `reviewReason` 等于冻结的原审核原因；
- 审核通过时 `approvedAt` 非空。

任何错号、错状态、错原因或缺少批准时间的 2xx 响应均保持 unknown。

### 4.2 提交已落库但响应丢失

5xx 后不开放立即重试。管理 GET 若返回：

- 通过路径 `WAIT_RETURN -> ... -> COMPLETED`、相同原因且有批准时间；或
- `REJECTED` 且原因相同；

则页面确认相同业务结果并清除 pending，但明确说明接口不公开命令 ID。

### 4.3 请求尚未到达 Trade

管理 GET 若仍为 `APPLIED`，只说明原审核效果尚不可见。此时才开放“使用原审核载荷
重试”，决定和原因保持只读。重试如果再次得到 5xx 或明确状态冲突，仍不能反推第一次
结果，必须重新读取权威事实。

### 4.4 其他管理员已经推进

权威状态与原决定相反，或审核原因不同，说明当前事实不能证明原载荷生效。pending
被明确终止，不允许继续重试，也不会把别人的审核归因给当前操作。

## 5. 页面组合

页面使用：

- `PjPageContainer`；
- `PjSurface`；
- `PjField`；
- `PjButton`；
- `PjActionGroup`；
- `PjStatusNotice`。

每条售后按一条连续逆向事实展示：

```text
当前 Trade 状态与处理方
  -> 顾客申请与审核原因
  -> 退货单 / 退款单
  -> 不可变订单行金额与优惠分摊
  -> 仅 APPLIED 可见的审核命令
```

页面没有按 Trade、Fulfillment、Inventory、Payment 分割成四组技术卡片。状态说明只做
展示，不复制后端状态机或驱动跨域命令。`REFUNDING` 明确不等于到账，
`REFUND_FAILED` 只指向 Payment 授权治理，不提供直接改成功入口。

## 6. 三层证据

| 结论 | 代码证明 | 自动化测试 | 真实 Chromium 运行证据 |
| --- | --- | --- | --- |
| 审核只能由 Trade 裁决 | `AfterSaleService.review` 行锁读取并要求 `APPLIED`；状态、历史、审核原因和 Outbox 同事务提交 | `AfterSaleFlowIntegrationTest` 4/4 覆盖整单快照、唯一售后、正逆向状态与 ADMIN/CUSTOMER 隔离 | Chromium 只向 Trade 管理审核接口 POST；明确响应匹配状态和原因后页面才显示 success |
| 提交已落库但响应丢失不重复 POST | entity 在 5xx 后冻结载荷且先调用 `adminAfterSale`；匹配决定、原因和批准时间才确认业务结果 | entity 单测模拟 POST 503、GET `WAIT_RETURN`，断言一次 POST、pending 清除且不伪造命令身份 | 受控服务先提交审核再返回 503；页面无 success、无重试按钮，权威 GET 后确认，浏览器总计一次 POST |
| 未落库时只能原载荷重试 | `retryAllowed` 只在权威 GET 返回 `APPLIED` 后开启；表单在 pending 期间只读 | entity 单测断言读取前重试不发请求，读取后两次 POST 的决定和原因逐字段一致 | Chromium 首次 POST 503 未提交，GET 仍 `APPLIED` 后才出现重试按钮；两次请求体完全一致，最终只有一个版本迁移 |
| 其他审核不能被当前操作冒领 | `authorityMatches` 同时校验状态路径、审核原因和批准时间 | entity 单测返回另一管理员的 `REJECTED` 与不同原因，断言 rejected、pending 清除、禁止重试 | 浏览器成功链的受控权威端记录唯一决定与原因；只有两者匹配时页面显示 accepted |
| 64 位身份与退款快照不被视觉迁移破坏 | entity 对 userId、skuId、行号和金额执行运行时校验；页面直接渲染不可变 `items` | entity 列表单测验证字符串 ID、金额和筛选 query；后端专项验证原订单价格分摊快照 | Chromium 显示完整 SKU 字符串、¥20.00 优惠分摊和 ¥378.00 可退金额 |
| 员工会话严格隔离 | pending storage key 按 operatorId 分区，access/token revision 阻止迟到响应写入 | 单测覆盖 pending 重建、operator 切换和 token 更新后的旧响应作废 | Chromium 通过 ADMIN 登录与真实路由守卫进入页面；Console warning/error、pageerror 和意外失败响应均为 0 |
| 状态展示不越权推进其他领域 | 页面映射九种状态的当前处理方，但审核表单只在 `APPLIED` 渲染 | 后端完整售后专项覆盖 Fulfillment、Inventory、Payment 事件逐步推进；前端单测不产生跨域 API | Chromium 审核后只显示 `WAIT_RETURN`，不会同步伪造退货单、库存回补或退款成功 |

浏览器证据使用真实 Chromium、Vue、Pinia、Foundation client 和 HTTP 请求。受控 HTTP
服务只用于稳定注入“提交后丢响应”和“请求未到达”两种故障，并记录浏览器实际请求；
它不替代 MySQL、RocketMQ、Inventory、Payment 的正确性。真实整单售后、库存回补、
退款与跨分片链继续引用 [交易服务](12-trade-service.md)、
[V5.3 售后与退款](89-frontend-visual-v5-3-after-sale-refund-20260802.md)和
[M0–M8 三层审查](69-m0-m8-pre-m9-three-layer-audit-20260728.md)。

## 7. 死代码与样式清理

迁移后确认以下规则没有 Vue、TS、E2E 或其他 CSS 消费者并删除：

- `.admin-toolbar`；
- `.admin-card-list`；
- `.admin-work-card`；
- `.admin-inline-form`；
- `.admin-form-wide`；
- `.admin-command-row`；
- `.admin-danger-text`。

旧 `frontend/admin-web/src/api/operations.ts` 也已零消费者。原聚合工厂把 Trade、
Fulfillment、Inventory、Marketing、Payment 与 Governance API 暴露为一个旧入口；
各管理域迁移到独立 entity 后已删除。Chat、Review 和首页仍使用的
`.admin-feedback`、`.admin-fact-grid`、`.admin-primary-action` 和
`.admin-boundary-note` 继续保留。

## 8. 最终门禁

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 45 / 45 |
| UI primitives | 5 / 5 |
| Storefront | 171 / 171 |
| Admin | 51 / 51 |
| 前端单元/契约测试合计 | 278 / 278 |
| After-sale entity 专项 | 8 / 8 |
| 分层规则 | 25 / 25 |
| 分层文件 / 相对导入 | 138 / 251 |
| Playwright 全量 Mock E2E | 54 / 54 |
| V6.4 专项 Playwright | 13 / 13 |
| Trade After-sale 后端专项 | 4 / 4 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 101.19 kB / gzip 14.33 kB
Storefront JS  356.90 kB / gzip 107.63 kB
Admin CSS      54.07 kB / gzip 8.10 kB
Admin JS       283.74 kB / gzip 85.41 kB
```

## 9. 下一坐标

V6.4.3 仅剩 Review、Chat 与管理首页。本批不迁移这三个页面，也不进入 V7。
