# M4 前端架构与毕业基线

> 日期：2026-07-21  
> 状态：M4 顾客端与管理端 V1 已完成

## 1. 结论

M4 已把后端现有自营 B2C 能力交付为一套 Vue 3 顾客端和一套角色受控管理端。实现遵循《素简记商城平台设计与实施计划书》：

- 不继承旧项目视觉、组件或布局；
- 不把订单建立、支付处理中、取消处理中、确认收货响应未知等中间状态解释为成功；
- 不用公开查询拼装管理事实；
- 不为尚无后端契约的列表或命令制造假数据；
- 浏览器业务 ID 保持字符串，不经过 JavaScript `Number`；
- MySQL、Redis、Nacos、RocketMQ、MinIO 的真实证据与前端 Mock E2E 分开记录。

## 2. 工程结构与基线

```text
frontend/
├── storefront-web/       顾客商城
├── admin-web/            运营与治理工作区
├── packages/foundation/  API 契约、领域类型、金额格式与设计令牌
└── e2e/                  Playwright 与受控 Mock API
```

| 能力 | 基线 |
| --- | --- |
| Node.js | 24.14.0，工程最低 22.12.0 |
| pnpm | 11.9.0 |
| Vue | 3.5.40 |
| Vue Router | 5.2.0 |
| Pinia | 4.0.2 |
| Vite | 8.1.5 |
| TypeScript | 6.0.3 |
| Vitest | 4.1.10 |
| Playwright | 1.61.1 |

TypeScript 固定为 6.0.3，因为当前 `vue-tsc 3.3.7` 尚不能使用 TypeScript 7 的包导出结构。请求层使用原生 `fetch` 与共享类型化客户端，不并行维护 Axios 实现。

## 3. 顾客端范围

| 路由 | 所有者域 | 已接通能力 |
| --- | --- | --- |
| `/`、`/products`、`/products/:productId`、`/search` | Catalog | 首页、分类、公开商品、SKU、媒体、URL 恢复 |
| `/index` | 前端 | 全局索引、青荷（默认）/素白主题选择 |
| `/login`、`/register`、`/account` | Identity | 注册、登录、刷新恢复、角色事实、明确退出边界 |
| `/account/addresses` | Identity | 新增、编辑、默认地址、删除确认 |
| `/bag` | Trade + 设备状态 | 游客袋、账户购物车、幂等合并、撤销 |
| `/checkout` | Identity + Catalog + Inventory + Marketing + Trade | 当前价格/库存/资格复核、稳定键下单、结果未知恢复 |
| `/orders`、`/orders/:orderNo` | Trade + Payment + Fulfillment | 订单、取消、支付、履约、物流、确认收货 |
| `/account/benefits` | Marketing | 当前账户权益与真实状态 |
| `/after-sales`、`/after-sales/:afterSaleNo` | Trade + Fulfillment + Payment | 整单售后、退货寄回、退款进度 |

顾客端明确区分：

- `PENDING_PAYMENT`：订单已建立且库存已预占，不等于支付成功；
- `PROCESSING` Payment：本地支付单存在，渠道最终结果仍待回调；
- `CANCELING`：释放与收敛仍在进行，不等于取消完成；
- Fulfillment `SIGNED`：确认收货的所有者域事实；
- Trade `COMPLETED`：履约事件经消息最终收敛后的订单事实；
- Refund `PROCESSING/NEEDS_ATTENTION`：退款未完成，不能显示到账。

## 4. 管理端范围与边界

| 路由 | 角色 | 已接通能力 |
| --- | --- | --- |
| `/catalog` | ADMIN / OPERATOR | 公开 ACTIVE 商品只读工作区 |
| `/fulfillment` | ADMIN / WAREHOUSE | 拣货、打包、发货、物流、异常、退货收货与验收 |
| `/after-sales` | ADMIN | Trade 售后列表、审核通过/拒绝 |
| `/inventory` | ADMIN / WAREHOUSE | 仓库列表/创建、库存查询、稳定流水号调整 |
| `/marketing` | ADMIN / OPERATOR | 规则创建、幂等权益发放 |
| `/governance` | ADMIN | 四域只读对账、Payment 退款重派和审计 |

下列能力没有对应后端契约，因此不属于 M4：

- Trade 通用管理订单列表；
- Payment 通用管理支付/退款列表；
- Catalog 草稿/下架列表和版本历史查询；
- Marketing 规则/权益管理列表；
- 跨领域统一修改业务事实。

管理端只调用所有者域接口。对账读取不会自动修复业务事实；Payment 补偿必须使用稳定命令 ID、ADMIN 权限和本地追加审计。

## 5. 主题系统

第一版只提供两套正式主题：

- **素白**：暖白画布、中性文字、苔灰绿行动色；
- **青荷**：淡水青画布、深叶墨文字、灰青边界和深荷叶青行动色。

主题入口位于全局索引的“页面气质”，不长期占用页面工具栏。选择写入 `localStorage["sujianji-theme"]`，刷新后恢复；切换改变画布、表面、媒体容器、文字、边界、强调色和焦点色，不改变价格、错误、退款等语义色。实现尊重 `prefers-reduced-motion`，没有荷花图片、纹样、水波或古典字体。

## 6. 浏览器业务 ID 与事件隔离

MyBatis-Plus Snowflake ID 可能超过 `Number.MAX_SAFE_INTEGER`。M4 规则为：

1. 浏览器响应 DTO 的 `Long` 业务 ID 局部序列化为 JSON string；
2. 前端统一使用 `BusinessId = string`；
3. URL、Pinia、`localStorage` 和请求参数保留原字符串；
4. 禁止全局修改 ObjectMapper 的 `Long` 语义；
5. Outbox 事件不得复用带浏览器序列化注解的 DTO。

最终审查发现 Trade `OrderPaid.deliveryAddress` 曾复用 `AddressSnapshotView`，使 `sourceAddressId` 被写为 JSON string，Fulfillment 旧消费者只接受 number，导致当前冒烟消息进入失败重试。修复后：

- Trade 使用独立事件 Map，`sourceAddressId` 保持 JSON integer；
- Fulfillment 同时接受正整数 number 和十进制正整数字符串，兼容已发布事件；
- 解析坏载荷记录 `NEEDS_ATTENTION` 并 ACK，业务异常执行有限重试；
- Trade 回归测试直接读取 Outbox JSON，防止浏览器 DTO 再次污染事件。

## 7. 结果未知恢复

| 操作 | 稳定事实/键 | 恢复策略 |
| --- | --- | --- |
| 游客袋合并 | 合并键 + 固定商品快照 | 保留本地商品，原键重试 |
| 下单 | `order:{uuid}` | 按幂等键查询 Trade，404 时保留原请求 |
| 取消订单 | `orderNo` | 查询 Trade，只接受真实 `CANCELING/CANCELED` |
| 创建支付 | `payment:{uuid}` | 按支付幂等键查询，不创建第二笔 |
| 确认收货 | `orderNo` | 查询 Fulfillment，只接受真实 `SIGNED` |
| 申请售后 | `after-sale:{uuid}` | 查询当前账户售后列表，原键安全重试 |
| 提交寄回 | `returnReceiptNo + carrier + trackingNo` | 查询退货单，不更换运单号盲重提 |
| 库存调整 | `movementNo` | 失败时保留流水号 |
| 退款重派 | `commandId` | 失败时保留命令 ID，并读取审计 |

待确认记录都绑定 `userId`。切换账户后，当前账户不会查询或重试另一账户在同一设备上的待确认命令。

## 8. 自动化与真实证据

### 8.1 前端门禁

`pnpm check` 当前包含：

- foundation 18 个 Vitest；
- storefront 49 个 Vitest；
- admin 2 个 Vitest；
- storefront/admin 类型检查；
- storefront/admin 生产构建；
- 2 个 Playwright E2E；
- 关键页面 axe serious/critical 违规为 0。

### 8.2 后端门禁

`mvn clean verify`：45 份 Surefire 报告、162 个测试，0 失败、0 错误、0 跳过。独立 PMD 3.28.0 / PMD 7.17.0：0 违规。

### 8.3 真实链路

- 权威结算、幂等下单和取消响应丢失见 [docs/36](36-m4-authoritative-checkout-and-order-recovery.md) 与 [docs/37](37-m4-order-center-and-cancellation-recovery.md)；
- 支付创建响应丢失、签名回调和所有者隔离见 [docs/38](38-m4-payment-and-unknown-result-recovery.md)；
- 履约时间线、确认收货响应丢失和 Trade 完成见 [docs/39](39-m4-fulfillment-and-logistics-timeline.md)；
- 顾客售后、管理端和完整五中间件毕业证据见 [docs/40](40-m4-customer-after-sale-admin-and-graduation.md)。

Mock API、H2 回归和真实中间件脚本分别证明浏览器交互、代码回归和运行机制，三者不能相互替代。

## 9. M4 后边界

M4 是 V1，不等于生产商城全部产品能力。密码找回、邮箱验证、评价/收藏、内容编排、承运商真实回调、生产 Web Server/History fallback 配置、SSR/SSG、管理查询 API 补齐等继续作为后续产品演进。

下一里程碑按总计划进入 M5 容量基线、SQL/连接池优化、缓存治理、背压和资源隔离，不跳到多商户或 Go 服务。

## 10. 2026-07-28 后续分层演进

M4 上述结构是阶段历史基线。M0–M8 完成后，前端开始采用
`design-system/foundation → shared → entities → features → pages → app` 的渐进式
分层：视觉令牌从 `foundation` 移入独立设计系统，顾客端应用壳层、导航和主题成为
第一批样板，并新增可执行依赖方向门禁。现有 `api/components/stores/views` 作为明确
迁移缝逐页收敛，不做一次性目录翻新，也不改变 M4 已验证的交易恢复语义。当前结论
与三层证据见 [前端低耦合分层第一批](71-frontend-layered-architecture-first-slice-20260728.md)。

第二批进一步把 Catalog 公开浏览链迁入 `entities/catalog` 与 `pages`，保留 URL
分类/搜索事实、字符串业务 ID、搜索竞态保护和降级提示；代码、自动化与真实浏览器
证据见 [前端低耦合分层第二批](72-frontend-catalog-layering-second-slice-20260728.md)。

第三批将跨 Identity/Trade 的顾客会话归入 feature，把纯本地游客袋状态归入下层
entity，并迁移登录、注册和账户页；刷新 401、传输异常、并发 restore、退出未知、
合并所有者和安全回跳均保持原语义。代码、自动化与真实浏览器证据见
[前端低耦合分层第三批](73-frontend-customer-session-layering-third-slice-20260730.md)。
