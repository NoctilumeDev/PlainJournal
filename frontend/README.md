# 素简记前端

前端采用 pnpm workspace：

```text
frontend/
├── storefront-web/   顾客商城
├── admin-web/        运营与治理工作区
└── packages/
    ├── design-system/ 品牌令牌与浏览器基础样式
    ├── ui/            无业务按钮、字段、状态、表面、行动组与页面容器
    └── foundation/   共享 API 契约、领域类型、格式化与客户端
```

运行要求：

- Node.js 22.12 或更高版本；当前基线为 Node.js 24。
- pnpm 11。
- TypeScript 6.0.3；当前 `vue-tsc 3.3.10` 尚不能使用 TypeScript 7 的新包导出结构。
- 本地开发默认通过 Vite 代理访问 `http://127.0.0.1:18000` Gateway。
- 使用 `PATH` 和 Corepack 解析 Node/pnpm，不依赖本机盘符或固定安装目录。

常用命令：

```bash
cd PlainJournal/frontend
corepack enable
pnpm install --frozen-lockfile
pnpm dev:storefront
pnpm dev:admin
pnpm check:boundaries
pnpm check
```

顾客端采用渐进式分层：

```text
design-system / foundation → shared → entities → features → pages → app
```

这是从底层能力到应用装配的组合顺序；源码只能由上层依赖下层。`app` 持有启动、
路由和跨域壳层，feature 只能通过公开 `index.ts` 被外部使用。同一 feature 不能
伸入另一个 feature 的内部目录。当前 `stores/views/styles` 是明确的迁移缝，
不假装已经完成；旧 `api/components` 已完成收敛，新下层模块禁止反向依赖剩余旧目录。规则由
`tools/check-layer-boundaries.mjs` 扫描，并已纳入 `pnpm check`。第二批已把公开
Catalog 浏览链收敛为 `entities/catalog` 与三个 page；不存在独立状态或复用动作的
页面不会为了目录对称强行包装成 feature。第三批把跨 Identity/Trade 的顾客会话
归入 `features/customer-session`，把纯本地游客袋状态归入
`entities/guest-bag`，避免把跨域工作流伪装成单一领域实体。第四批把 Identity
地址事实归入 `entities/address`，由账户 page 显式组合当前会话；地址 entity
不反向读取 session，owner/token 变化会使旧请求失效。第五批把 Trade 账户购物车
归入 `entities/account-cart`，由 BagPage 显式组合设备游客袋、账户事实和会话合并；
账户 entity 不读取 session，owner/token/revision 阻止迟到响应串户，写入结果未知
时必须先重新读取 Trade。第六批把跨 Trade、Identity、Catalog、Inventory、
Marketing 的结算工作流归入 `features/checkout`，由薄 `CheckoutPage` 显式传入
会话上下文；owner/token/access revision、草稿指纹和计算代次共同阻止旧响应覆盖，
owner-scoped pending order 防止账户间恢复串扰。第七批把 Trade 订单事实归入
`entities/order`，把 Payment 意图和结果未知恢复归入 `features/order-payment`；
订单 page 只做旅程装配，owner/token/访问代次、稳定幂等键和响应身份校验阻止
跨账户迟到写入，Payment 未就绪、未知、处理中或成功时取消入口失败关闭。第八批把
Fulfillment 权威履约/位置事实归入 `entities/fulfillment`，把确认收货意图、并发
合并和结果未知恢复归入 `features/order-fulfillment`；旧账户/旧令牌响应、订单或
所有者身份不匹配均不能写入当前页面，Trade 完成状态继续等待消息收敛后查询。第九批
把 Trade 售后、Fulfillment 退货和 Payment 退款分别归入 `entities/after-sale`、
`entities/return-receipt` 与 `entities/refund`，再由 `features/after-sale-workflow`
组合为连续旅程；三份事实不互相改写，并发寄回合并、不同运单冲突、迟到响应和结果
未知均失败关闭。第十批把 Catalog 评价事实归入 `entities/product-review`，订单完成后
的唯一评价意图归入 `features/order-review`，商品公开反馈、点赞与举报归入
`features/product-reviews`；稳定评价键、owner-scoped 恢复事实、访问代次与参与动作
互斥阻止重复提交、串户和迟到响应反向覆盖。

生产环境默认使用同源 `/api`。如入口不同，可通过 `VITE_API_BASE_URL` 指定 Gateway 基地址。

当前已接通公开 Catalog、顾客注册登录、刷新令牌会话恢复、明确退出边界、账户与地址管理，以及员工 ADMIN/OPERATOR/WAREHOUSE 角色门禁。游客购物袋保存在当前设备；登录后通过 Trade 幂等合并接口累加到账户购物车，服务端事务成功前不会清除本地商品，超时或网络失败会保留原请求键供安全重试。

`/bag` 在登录后读取 Trade 账户购物车，支持数量、是否纳入结算和二次确认删除；
网络、超时、非法响应或 5xx 不伪造成成功，而是先重新读取所有者事实。登录合并继续
复用稳定键和固定请求快照，响应丢失时保留设备商品。`/account/addresses` 支持地址
新增、编辑、默认地址切换和原位删除确认。`/checkout` 会先读取购物车展示快照，再
重新查询 Catalog 当前商品与价格、Inventory 权威库存和 Marketing 当前资格；权威
结果只在 60 秒内有效，价格变化和库存不足都明确展示。并发提交合并为一个活动请求；
购物车、地址、地区或权益变化会使旧复核结果失效。

订单提交使用 `order:{uuid}` 稳定幂等键和固定请求快照。网络、超时、非法响应或 5xx 后，前端按原键查询 Trade；查询仍为 404 时保留原请求与原键供安全重试，不换键盲目重提。`/orders` 与 `/orders/:orderNo` 由薄 page 组合 Trade 订单实体和 Payment feature，展示商品/地址/价格不可变快照与真实状态，并继续接通履约时间线、确认收货和整单售后入口。Payment 使用稳定 `payment:{uuid}`；支付创建响应未知时按原键恢复，确认收货响应未知时查询 Fulfillment 所有者事实，均不把连接异常解释为成功。

顾客端还提供优惠权益、售后列表/详情、退货寄回和退款进度。售后详情同时展示 Trade、
Fulfillment、Payment 三份原始状态、当前处理方和下一步；没有承运商 SLA 契约时不
伪造预计完成时间，退款处理中或 `NEEDS_ATTENTION` 时不显示到账。“青荷”是默认主题，
“素白”作为备用，两者只改变完整设计令牌，并从全局索引切换后持久化。青荷以荷青
为主骨、淡青与远天蓝为辅助，不使用荷花图片或国风装饰；语义色不随主题变化。
管理端按后端真实契约开放履约与退货、售后审核、仓库/库存调整、营销创建/发放、
Payment 授权补偿和四域只读对账；缺少列表或通用管理契约的领域明确保持受限，不
拼装虚假成功。

前端视觉 V1–V7.4 已全部完成。顾客端
商品发现、购物袋、结算、订单、Payment、Fulfillment、售后、账户、通知与 Chat，
以及管理端九个权限工作区已经迁移到同一套令牌、primitives 和连续事实语言。两张
商品图和一张履约图保留 PNG fallback，同时生成 18 个 AVIF/WebP 响应式变体；首页、
目录、详情、购物袋、管理 Catalog 和履约插图通过共享 `PjResponsiveImage` 按视口
选择来源。V7.3 进一步提供夹具专用演示账号、生产 preview、Nginx/Compose 静态部署、
History fallback、分级缓存、Gateway/WS 边界和版本标签回退。V7.4 又完成真实双
Nginx 镜像构建、OCI 元数据、Header/404、同源 API、HEALTHCHECK、两个不可变标签
回退与恢复，以及 README/CHANGELOG/SECURITY/Release checklist 和三张最终截图。
2026-08-21 最新串行门禁已覆盖单元/契约、开发态与生产构建 Playwright、分层、
交付、部署、类型检查、两端构建和关键页面 axe 检查；精确计数只维护在仓库生成的
[`docs/verification-summary.md`](../docs/verification-summary.md)。真实 Chromium
网络取证证明当前选择 `image/avif`，且没有把 PNG fallback 作为图片下载；生产构建还
验证两端深层刷新、同源 API、错误密码 401 和管理守卫恢复。
`frontend/e2e/mock-api.mjs` 只用于稳定浏览器验收，不替代真实 MySQL、Redis、Nacos、
RocketMQ、MinIO、Gateway 和所有者服务证据。

当前前端交付边界、真实浏览器链路和工程冻结证据见
[`v1.0.2` 工程验收快照](../docs/evidence/v1.0.2-engineering-acceptance-20260804.md)；
逐批视觉迁移记录由 Git 历史追溯，不再随主分支持续携带。
当前前端已冻结为 `v1.0.10` 验收候选，基础演示和 E2E 编排使用跨平台 Node 入口，并以
四包聚合行覆盖率 70% 作为公开门禁；复杂 Docker 和真实故障脚本继续使用
PowerShell 7。仓库采用 Apache-2.0，后续只处理明确缺陷，不扩大当前自营业务边界；
视觉系统重构不预留具体 `1.x.x` 版本，待方案成熟后再按实际变更发布。
