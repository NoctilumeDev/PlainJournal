# 前端低耦合分层第十批：评价事实、参与竞态与结果未知恢复

> 日期：2026-08-01  
> 状态：本批完成；顾客端主要交易旅程的结构迁移收口  
> 边界：不改变 Catalog、Trade 的事务、消息或审核语义；不在本批引入新的卡片或视觉语言

## 1. 本批目标

旧商品详情页和订单详情页共同依赖全局 `stores/reviews.ts`。该 store 直接读取实时
session，同时持有公开评价、订单评价资格、提交、点赞和举报状态；页面还理解结果
未知恢复、身份隔离和参与动作互斥。这种结构在正常请求下能运行，但无法独立证明
账户切换、旧令牌迟到响应、并发重复提交和响应丢失不会污染当前页面。

本批按所有权与用户意图拆成三块：

```text
entities/product-review
  Catalog 公开评价、评分汇总、顾客资格和参与事实

features/order-review
  完成订单后的唯一评价意图、稳定键与结果未知恢复

features/product-reviews
  商品公开反馈、点赞和举报参与
```

`ProductDetailView.vue` 与 `OrderDetailView.vue` 现在只构造显式访问上下文并组合 feature。
旧 `stores/reviews.ts` 及其重复测试已经删除，不保留第二套状态源。浏览器审查同时发现
订单详情嵌套两个 `<main>`；内层已改为普通布局容器，页面恢复单一主区域。

## 2. 高风险边界

### 2.1 所有者、令牌和本地恢复事实

评价 entity 不读取实时 session。调用者必须显式传入 `authenticated + ownerId +
accessToken`；owner 或 token 变化会推进访问代次、清除个性化公开事实和资格事实，并
使旧请求响应失效。待确认评价按 owner 使用 `plain-journal:pending-review:v2:<ownerId>`
保存；v1 旧键只在所有者匹配时迁移。

### 2.2 一次资格、稳定意图和响应丢失

同一资格、同一评分、正文和匿名选择的并发提交合并为一个 POST；活动请求期间不同
载荷失败关闭。请求使用稳定 `review:{uuid}`。网络、超时、非法响应或 5xx 后不显示
成功，而是先查询 Catalog 资格：只有同一资格已经变为 `REVIEWED` 且带评价 ID，才
重新读取商品公开事实并恢复成功；这次查询失败或被更新请求取代时不允许使用旧缓存
资格恢复。仍无法确认时保留原所有者、原载荷和原键。

### 2.3 点赞、举报和非幂等风险

同一点赞目标的并发动作合并，错误评价 ID、错误状态或旧 token 响应不能写入当前
页面。点赞和举报共享同一评价参与边界，不能交错覆盖。举报接口没有客户端幂等键，
因此响应丢失只显示“结果尚未确认”，页面不会自动重提；这是显式保留的 API 能力
边界，不以第二次 POST 猜测成功。

## 3. 三层证据

下表每条结论均同时保留代码、自动化和真实运行三层。当前内置浏览器使用受控 HTTP
夹具验证页面和请求行为；真实 MySQL/RocketMQ 资格、并发唯一性和审核事实继续由
`docs/65` 与 `docs/69` 裁决，不能用浏览器夹具替代。

| 结论 | 代码证明 | 自动化测试 | 真实运行证据 |
| --- | --- | --- | --- |
| A 账户、旧令牌或错误商品响应不能污染当前页面 | `productReviewStore.ts` 使用 owner/token/access revision，并校验 summary、review、eligibility 的业务身份；pending 键按 owner 隔离 | entity 测试覆盖 A→B 资格迟到、旧 token 点赞迟到、错误 productId/reviewId 和 owner-scoped unknown | 当前浏览器在已登录顾客订单与对应商品之间只展示该顾客资格；历史真实链验证跨账户 404、资格所有者隔离，见 `docs/65`、`docs/69` |
| 同一评价意图只提交一次，变载荷并发失败关闭 | 活动提交按资格和完整载荷指纹合并；不同载荷拒绝；稳定键保留到事实确认 | 两个同载荷调用只产生一次 POST；活动期间不同内容拒绝；Playwright 断言响应丢失场景仍只有一个 `review:` 键和一个 POST | 当前浏览器从 `ELIGIBLE` 提交后只出现一个 `REVIEWED` 事实和一条新公开评价；真实 MySQL 8 路重试全部收敛为同一评价 ID |
| 提交响应丢失不会伪造成功或换键重提 | POST 失败后只接受本次 Catalog 新鲜资格查询；仅 `REVIEWED + reviewId` 且商品匹配可恢复，查询失败不能回用旧缓存 | entity 测试覆盖丢响应后资格恢复、fresh 查询失败仍保持 unknown 和不可确认分支；E2E 让上游完成 POST 后主动丢弃响应，页面通过资格恢复且 POST 次数为 1 | 当前浏览器正常链显示“Catalog 已保存评价事实”；历史 RocketMQ 停机时 Trade Outbox 保持 `PENDING`、恢复后资格才收敛，证明中间件异常不会提前生成成功事实 |
| 点赞与举报不能被迟到响应或交错动作反向覆盖 | 点赞校验目标 ID 和期望状态；参与动作互斥；举报响应未知不自动重提 | 测试覆盖旧 token 响应丢弃、错误点赞响应拒绝、点赞期间举报阻断、同一举报合并和响应丢失零自动重试 | 当前浏览器“有用 · 0”变为“取消有用 · 1”，举报保存后出现平台审核提示；真实 Catalog 链验证点赞/举报所有者约束及审核治理 |
| 分层后页面仍是一条连续旅程且语义结构正确 | 两个 view 只消费 feature 公开入口；15 条规则禁止 feature 反向依赖 page/session；重复 `<main>` 已移除 | 组件测试覆盖两项 feature 装配；类型检查、生产构建和 axe 通过 | 内置浏览器桌面内容宽度 1265、移动内容宽度 375，均 `mainCount=1`、横向溢出 0、控制台 warning/error 0 |

真实后端证据：

- [M8 第十批：商品评价、并发幂等与审核治理](65-m8-product-reviews.md)
- [M0–M8 三层证据审查](69-m0-m8-pre-m9-three-layer-audit-20260728.md)

当前浏览器证据保存在本机忽略目录：

```text
backend/.run/frontend-review-tenth-20260801/
  product-review-browser.png
  product-review-browser-mobile.png
  browser-verification.json
```

## 4. 最终门禁

关闭本批人工浏览器夹具后，从头串行执行 `pnpm check`：

```text
Foundation Vitest             42
Storefront Vitest            119
Admin Vitest                  12
Vitest total                 173
Playwright Mock E2E           14
layer rules                   15
layered files / imports       96 / 206
typecheck / build / axe       PASS
desktop/mobile overflow       0 / 0
browser main count            1 / 1
browser console warn/error    0 / 0
```

Storefront 生产构建转换 176 个模块，主 JS 为 314.82 kB、gzip 97.41 kB。人工浏览器
夹具先独占 18000/18200，结束后再让 Playwright 串行启动自身服务，避免在同一台
Y7000P 上并行争抢端口、Node 进程和代理资源。

## 5. 视觉与发布边界

本批只拆职责，没有给每块积木新增边框、阴影或卡片。当前订单页信息密度高、商品页
评价区和既有购买区缺乏统一节奏，浏览器截图如实保留了这个问题；它是下一轮全局
视觉重构的输入，不应在结构迁移期间由局部 CSS 补丁掩盖。

顾客端主要交易旅程的结构迁移到此收口。下一阶段以“青荷”为默认气候、以“素白”
为备用主题，从全局版式、信息层级、留白、商品媒体和行动优先级统一重构，再完成演示
数据、部署文档和 GitHub v1.0 发布。当前仓库不进入多商户与 Go 改造；该演进保留给
未来独立“素简记 Pro”。
