# 前端低耦合分层第九批：售后三域事实与寄回竞态治理

> 日期：2026-08-01
> 状态：本批完成；当前仓库继续收口自营 B2C v1.0，不进入多商户改造
> 边界：不改变 Trade、Fulfillment、Payment 的状态机、事务或消息语义；页面只组合三个所有者域的权威事实

## 1. 本批目标

旧售后详情页同时持有 Trade 售后、Fulfillment 退货、Payment 退款、会话读取、
寄回命令、结果未知恢复和大段页面标记。业务能跑通，但页面层过度理解三个领域，
账户切换、迟到响应和并发提交的安全边界也难以独立证明。

本批按真实所有权拆为四块积木：

```text
entities/after-sale
  Trade 售后事实、稳定申请键、取消与结果未知恢复

entities/return-receipt
  Fulfillment 退货事实、不可覆盖运单、提交结果未知恢复

entities/refund
  Payment 退款事实；PROCESSING / NEEDS_ATTENTION 不包装成成功

features/after-sale-workflow
  把三个事实拼成一条顾客旅程，但不复制任一领域状态机
```

`AfterSaleDetailView.vue` 现在只负责读取路由售后号、构造显式访问上下文并装配
workflow。旧 `stores/afterSales.ts`、`stores/returns.ts`、`stores/refunds.ts` 和根级
`afterSaleStatus.ts` 已删除，不再保留两套状态源。

## 2. 高风险边界

### 2.1 账户、令牌与迟到响应

三个 entity 都不读取实时 session。调用者必须显式传入 `authenticated + ownerId +
accessToken`；owner 或 token 变化会推进访问代次并使旧响应失效。响应中的 `userId`、
`afterSaleNo`、`returnReceiptNo` 等身份还必须与当前请求及已知事实一致，错误所有者或
错误业务号不能写入页面。

待恢复的售后申请使用 owner-scoped 本地键；v1 旧键只在所有者匹配时迁移，不能把 A
账户未完成的命令交给 B 账户恢复。

### 2.2 并发寄回与结果未知

同一退货单、同一访问代次、同一承运商和运单的并发提交合并为一次 POST。活动请求
期间提交不同运单会失败关闭；服务端已经保存寄回事实后，页面不允许覆盖运单。

网络、超时、非法响应、5xx 或响应身份不匹配都不是成功。页面查询 Fulfillment：

- 查询到同一承运商和运单，且状态已离开 `WAIT_SHIPMENT`：恢复为已提交；
- 查询仍为 `WAIT_SHIPMENT`、事实不匹配或查询失败：保持结果待确认；
- 不更换运单、不开第二个请求键盲目重试。

### 2.3 三域状态不互相冒充

Trade 决定整单售后状态，Fulfillment 决定寄回、收货和验收，Payment 决定退款派发
与渠道结果。页面同时展示三份原始状态：例如寄回成功后允许
`RETURNING / RETURNING / PROCESSING`，不会因为仓库流程推进而提前显示资金到账。
`NEEDS_ATTENTION` 是需要治理的异常，不是退款成功。

进度区展示当前处理方、下一步和时间说明。没有承运商 SLA 契约时明确说明“不估算
到仓时间”，不生成虚假的预计完成时间。

## 3. 三层证据

本表每条结论均同时保留代码、自动化和真实运行三层，不把日志本身当业务结果：

| 结论 | 代码证明 | 自动化测试 | 真实运行证据 |
| --- | --- | --- | --- |
| 旧账户、旧令牌和错误身份响应不能串户 | 三个 entity 的 owner/token/revision 与返回身份校验；售后待恢复键按 owner 隔离 | store 测试覆盖 A→B、错误 userId、错误售后号、错误退货单号和迟到响应 | 当前内置浏览器只显示当前顾客的售后、订单和退货单；历史真实链的跨账户读取为 404，见 `docs/40` 与 `docs/69` |
| 同一寄回意图不会并发重复提交或覆盖运单 | `returnReceiptStore.ts` 合并同载荷活动 Promise，并拒绝不同载荷与已提交后的覆盖 | 两个同载荷调用只产生一次 POST；活动期间不同运单拒绝；已提交后零 POST | 当前内置浏览器只产生一次提交动作，页面随后显示唯一运单 `SF / SF202608010001`，刷新后的 Fulfillment 事实仍一致 |
| 响应丢失不伪造寄回成功 | 失败后只查询 Fulfillment；必须匹配业务号、承运商、运单且离开 `WAIT_SHIPMENT` | 覆盖丢响应后匹配事实恢复、旧账户响应丢弃和取消结果未知恢复 | 当前浏览器在实际 HTTP 提交前后显示 `WAIT_SHIPMENT -> RETURNING`；历史真实正逆向链验证寄回、收货、验收、库存回补与退款收敛 |
| 退款处理中或治理异常不会显示到账 | `refundStatus.ts` 将 `PROCESSING` 与 `NEEDS_ATTENTION` 分别映射为处理中与危险治理态；workflow 只读 Payment 事实 | refund entity/status 测试覆盖访问隔离、错误响应和 `NEEDS_ATTENTION` 展示 | 当前浏览器寄回后仍显示 Payment `PROCESSING`、渠道请求 `PENDING / 0 次`、成功时间 `—`；真实退款派发、签名回调与补偿证据见 `docs/40` |
| 页面是连续旅程而非三个孤立后台表单 | `AfterSaleProgress.vue` 与 `AfterSaleWorkspace.vue` 组合当前处理方、下一步、三域事实和不可变商品快照 | 组件测试、14 条分层规则、axe 与生产构建共同约束结构 | 内置浏览器桌面与 390×844 视口均无横向溢出，只有一个 `main`，控制台 warning/error 为 0 |

历史真实后端证据：

- [M4 顾客售后、管理端与毕业报告](40-m4-customer-after-sale-admin-and-graduation.md)
- [M0–M8 进入原 M9 前三层证据审查](69-m0-m8-pre-m9-three-layer-audit-20260728.md)

当前浏览器证据保存在本机忽略目录：

```text
backend/.run/frontend-after-sale-ninth-20260801/
  desktop-before-shipment.png
  desktop-after-shipment.png
  mobile-after-shipment.png
  verification.json
```

本批没有重新启动完整 MySQL、Redis、Nacos、RocketMQ 与八个交易应用，因此不把当前
受控浏览器夹具冒充成新的全栈实证；后端状态机与 API 契约没有在本批修改，资金、
库存和退款的真实结论继续引用已经完成的全栈证据。

## 4. 最终门禁

修复进度区 4.42:1 的文字对比度后，串行 `pnpm check` 通过：

```text
Foundation Vitest             42
Storefront Vitest            108
Admin Vitest                  12
Vitest total                 162
Playwright Mock E2E           14
layer rules                   14
layered files / imports       87 / 199
typecheck / build / axe       PASS
desktop/mobile overflow       0 / 0
browser console warn/error    0 / 0
```

Node 基线仍为 `D:\Node.js\current\node.exe` 24.14.0；pnpm 11.9.0 当前由用户级 npm
命令目录提供。两者解析到同一 Node 运行时，没有新增第二套 Node 环境变量。

## 5. 项目边界

当前仓库以完整自营 B2C 产品作为 v1.0 交付边界。原 M9 的三个商户、多主体订单、
分账结算和 Go 异构服务不再进入本仓库；它们保留为未来独立仓库“素简记 Pro”的
平台化演进课题。当前后续工作只继续产品化前端、演示数据、部署与 GitHub 发布收口。
