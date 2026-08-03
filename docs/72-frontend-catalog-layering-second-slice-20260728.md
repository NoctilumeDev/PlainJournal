# 前端低耦合分层第二批：Catalog 公开浏览链

> 完成日期：2026-07-28  
> 范围：Catalog 公开实体、共享异步状态、首页、商品列表、搜索页和依赖门禁  
> 边界：不进入 M9，不改变后端接口、商品事实、交易语义、权限事实或状态机

## 1. 结论

第二批把公开商品浏览链迁入
`shared → entities/catalog → pages → app`，建立第一个完整的实体与页面组合样板。
这里没有为了目录对称创建空的 Catalog feature：公开首页、分类列表和搜索页只是读取并
展示 Catalog 事实，由 page 直接组合 entity 更准确。只有出现可复用的用户动作和独立
状态边界时，才新增 feature。

本批继续遵循“代码边界强、视觉边界弱、状态边界明确、用户旅程连续”。商品卡片、
网格和页面样式跟随所有者组件，但页面仍保持青荷主题下的一体化留白、节奏和颜色关系，
没有把分层表现成卡片套卡片或可见的技术边界。

## 2. 代码边界

```text
storefront-web/src/
├── shared/ui/
│   ├── AsyncState.vue
│   ├── index.ts
│   └── styles.css
├── entities/catalog/
│   ├── api/catalog.ts
│   ├── ui/
│   │   ├── CatalogAsyncState.vue
│   │   ├── ProductCard.vue
│   │   └── ProductGrid.vue
│   └── index.ts
├── pages/
│   ├── home/HomePage.vue
│   ├── catalog/ProductListPage.vue
│   └── search/SearchPage.vue
└── app/router.ts
```

- `entities/catalog/index.ts` 是 Catalog 的唯一公开入口，页面和旧商品详情页不能伸入
  `api` 或 `ui` 内部目录。
- `shared/ui/index.ts` 是共享 UI 的公开入口；通用 `AsyncState` 不理解 Catalog，
  `CatalogAsyncState` 只负责把领域文案映射到共享状态。
- `ProductGrid` 统一商品集合结构，`ProductCard` 持有自己的视觉样式；首页、列表和
  搜索页只持有页面编排与页面级样式。
- 路由只装配 page，不直接拼 Catalog 内部实现。
- 迁移后旧 `src/api` 与 `src/components` 已无文件，两个空目录已删除；没有保留双份
  适配器或兼容转发层。

## 3. 必须保持的事实

| 事实 | 保持方式 |
| --- | --- |
| 业务 ID 不能经过 JavaScript number | `BusinessId` 继续为 string；卡片路由参数原样使用 `product.id` |
| 分类选择是可分享的页面事实 | 分类 slug 保留在 `?category=`，页面从 route query 派生选择状态 |
| 搜索词是可刷新恢复的页面事实 | 搜索词保留在 `?q=`，输入框和结果标题从 URL 恢复 |
| 慢响应不能覆盖较新的搜索 | `requestSequence` 继续丢弃过期响应 |
| 搜索降级不能伪装成正常索引 | `degraded=true` 继续显示 MySQL 降级提示 |
| 页面不能自行制造商品成功事实 | loading、error、empty 与产品结果都来自 Catalog 调用结果 |

商品详情仍属于后续高风险组合页，本批只把它对 Catalog 的导入收敛到公开入口，没有
迁移其购物袋、评价或交易动作。

## 4. 三层验证

| 层级 | 证据 |
| --- | --- |
| 代码 | Catalog API、卡片、网格和异步状态归入实体；三个公开浏览页归入 pages；共享 UI 通过公开入口使用；旧路径引用为 0；页面与卡片样式跟随所有者 |
| 自动化 | 6 个 Node 分层规则测试通过，实际扫描 24 个分层文件和 168 条相对导入；Foundation 41、Storefront 54、Admin 12，共 107 个 Vitest；8 个 Playwright E2E；两端类型检查与生产构建通过 |
| 真实浏览器 | 桌面从首页真实点击分类、打开搜索、提交“通勤”、刷新并进入商品详情；分类和搜索 URL 保持，`2079000000000000001` 未丢精度；移动视口搜索页为两列商品网格且水平溢出为 0；全过程控制台 warn/error 为 0 |

浏览器运行时观察到 93 项资源，其中实际加载了 `shared/ui/styles.css`、
`pages/home/HomePage.vue`、`pages/catalog/ProductListPage.vue`、
`pages/search/SearchPage.vue`、`entities/catalog/index.ts`、Catalog API、
`ProductCard`、`ProductGrid` 和 `CatalogAsyncState`。这证明新边界进入真实 Vite
模块图，不只是静态文件移动。

本批浏览器使用受控 API 夹具验证前端结构、URL、字符串身份、交互、响应式布局和运行时
模块边界。它不替代 Catalog 的真实 MySQL、OpenSearch、故障降级与对账证据；这些机制
继续以 M0–M8 三层审查及
[M8 商品搜索报告](66-m8-catalog-search.md)为准。本批没有修改资金、库存、权益、
订单或权限代码。

## 5. 体积与视觉取舍

`storefront.css` 从第一批后的约 2372 个物理行收敛到 2034 行；Catalog 所有者样式
不再由全局文件隐式控制。最终 Storefront 构建约为：

```text
CSS 47.37 kB / gzip 8.12 kB
JS  270.53 kB / gzip 83.52 kB
```

相对第一批，CSS gzip 增加约 0.30 kB，主要来自组件 scoped 选择器带来的所有权标识。
这是用极小体积换取局部样式可追踪性的明确取舍，不解释为页面性能已经优化；后续仍以
真实页面资源和浏览器表现判断。

## 6. 下一最小风险切片

下一批建议只收敛 Identity 会话边界和登录、注册、账户页：

1. 先建立 Identity entity 的公开入口，不重写令牌刷新、跨标签同步或路由守卫；
2. 将会话事实作为其他 feature 的显式输入，禁止各 feature 自行恢复或解释身份；
3. 登录、注册和账户页迁入 pages，保留旧刷新令牌 401 的中文恢复语义；
4. 增加所有者隔离、刷新恢复、退出清理、URL 回跳和真实浏览器证据；
5. 购物袋合并、Checkout、订单、Payment 和售后继续留在后续独立批次。

M9 仍冻结。前端分层不改变 M0–M8 的业务完成度，也不构成进入三个商户或 Go 异构服务
的授权。

## 7. 后续记录

上述数字是第二批完成时的历史证据。顾客会话、登录/注册与账户页已经按本节建议完成
第三批分层，最新门禁与三层证据见
[前端低耦合分层第三批](73-frontend-customer-session-layering-third-slice-20260730.md)。
