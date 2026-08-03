# 前端视觉 V6.4.3：Catalog 管理观察窗

> 完成日期：2026-08-03  
> 状态：V6.4.3 Catalog 切片已完成；V6.4.3 仍剩 After-sale、Review、Chat 与管理首页  
> 范围：公开 ACTIVE 商品投影、分类筛选、分页、图片与员工会话隔离  
> 硬边界：未新增 Catalog API，未修改商品状态机、写命令、读副本路由或 MySQL 最终事实

## 1. 本批为什么不是完整商品经营后台

Catalog 后端已经拥有分类、品牌、商品、SKU、上下架和媒体等管理命令，但当前没有管理端
商品列表或详情 GET 契约，Foundation 也没有覆盖相应管理写 API。如果只为视觉迁移把
公开商品接口包装成“经营后台”，会产生三个错误：

- `DRAFT` 和 `INACTIVE` 商品被公开接口过滤，页面无法证明完整目录事实；
- 公开读可能命中读副本，不能宣称刚完成的管理写已经可见；
- 创建、修改、上下架和媒体命令尚未在前端建立幂等或结果未知恢复协议。

因此当前 `/catalog` 的正式定位是：

> 管理端中的公开 ACTIVE 商品观察窗，不是 Catalog 所有者域的完整经营工作台。

以后建设完整经营后台时，必须先补充管理读模型、Foundation 管理 API 和写命令恢复
契约，再作为独立业务切片实施，不能由视觉批次暗中扩建。

## 2. 代码结构

旧页面直接读取公开 API，并依赖已经没有消费者的全局表格样式。迁移后依赖方向为：

```text
Foundation public Catalog API
          ↓
entities/admin-catalog
  - public DTO runtime validation
  - operator/token access revision
  - request revision / newest response wins
  - filters and page facts
  - preserve last known projection on failure
          ↓
CatalogReadOnlyView
  - primitives composition
  - product image and fallback
  - explicit public-projection wording
```

页面只从 `entities/admin-catalog/index.ts` 使用公开入口。entity 不依赖旧 session store，
而是显式接收 `authorized + operatorId + accessToken`。公开 Catalog client 自身不携带
员工 token，避免失效 Bearer Token 干扰 `permitAll` GET；员工身份只用于管理路由准入
和本地响应代次隔离。

## 3. 投影与身份契约

### 3.1 公开 ACTIVE 边界

页面只读取：

```text
GET /api/v1/catalog/categories
GET /api/v1/catalog/products?page={page}&size={size}
```

筛选时只增加公开契约已有的 `categoryId` 和 `keyword`。页面文案明确说明：

- 当前结果只包含公开 `ACTIVE` 商品；
- 总数只属于当前公开投影；
- 草稿、下架商品和完整经营目录不在当前观察范围；
- 投影可能来自副本，不等同于最新主库事实。

### 3.2 64 位业务 ID

商品、分类、父分类和品牌 ID 必须是十进制字符串。运行时校验拒绝数字 ID、空 ID 或
缺失身份，Vue 的 key、筛选值和页面展示均保持字符串，避免 JavaScript 安全整数截断。

### 3.3 分页与响应错归

entity 保存当前 `page / size / total` 并验证：

- 响应页码和页大小必须等于请求；
- `total` 必须是非负整数；
- 当前页数量不能超过页大小或总数；
- 同一页不能出现重复商品 ID。

每次请求递增 revision。较早请求晚于新筛选返回时静默作废；员工或 token 切换也会让
旧响应失效。错页、非法 DTO 或重复身份不会替换页面已有事实。

## 4. 读取失败与图片语义

公开投影刷新遇到 503、超时、网络异常或非法响应时：

- 保留上一次已经显示的商品数组和分页事实；
- 明确显示“读取未完成”；
- 不把失败伪装成零商品；
- 下一次刷新成功后再以新权威投影替换。

商品图片使用公开 DTO 的 `coverUrl`。图片为空或浏览器加载失败时，只把对应媒体区域
降级为“无图片”，商品标题、品牌、分类、金额和字符串 ID 仍继续显示。图片故障不会
抹掉商品文字事实。

## 5. 页面组合与响应式

页面迁移到：

- `PjPageContainer`；
- `PjSurface`；
- `PjField`；
- `PjButton`；
- `PjActionGroup`；
- `PjStatusNotice`。

页面提供关键词、分类、清除、刷新、上一页和下一页操作，并展示真实商品图片、品牌、
分类、价格、字符串 ID 与当前分页范围。桌面使用媒体与事实双列，390px 和 320px
收敛为单列，不产生根级横向溢出。

旧 `frontend/admin-web/src/api/catalog.ts` 已确认零消费者并删除。旧
`.admin-table-wrap` 及其 table/th/td 子规则也已确认没有 Vue、TS、E2E 或其他 CSS
消费者后删除。After-sale、Review、Chat 和首页仍使用的 `.admin-page__header`、
`.admin-notice`、`.admin-state`、`.admin-text-button` 等规则继续保留。

## 6. 三层证据

| 结论 | 代码证明 | 自动化测试 | 真实浏览器运行证据 |
| --- | --- | --- | --- |
| 页面只观察公开 ACTIVE 投影 | `adminCatalogStore` 只调用 Foundation 的 `listCategories/listProducts`；`CatalogReadOnlyView` 不包含管理写动作 | `CatalogFlowIntegrationTest` 验证 CUSTOMER 不能写、DRAFT 对外 404、发布后公开可读；分层规则禁止页面绕过 entity | Playwright 抓取浏览器请求，确认只访问 `/api/v1/catalog/products`，筛选请求为公开 query，页面明确显示公开投影边界 |
| 64 位 ID 不被截断 | entity 的 `isBusinessId`、分类/品牌/商品校验只接受十进制字符串 | entity 单测断言 `2087000000000000201` 保持字符串；后端响应测试断言媒体 ID 为字符串 | Chromium 页面显示完整商品 ID，商品与分类筛选使用字符串值完成交互 |
| 分页与并发响应不发生错归 | `validateProductsPage` 校验页码、页大小、总数和重复 ID；`productsRevision/accessRevision` 裁决迟到响应 | entity 单测覆盖真实分页 query、旧请求迟到、错页响应和 operator 切换 | Playwright 验证页面范围、页数、筛选后总数和请求参数一致，320/390px 交互后无溢出 |
| 503 不伪装成空目录 | `loadProducts` 失败只写 `productsError`，不清空 `products/total` | entity 单测先成功再返回 503，断言已知商品保留 | Playwright 注入一次 503，页面显示失败说明且仍保留两个商品；再次刷新成功后错误消失 |
| 图片失败不破坏文字事实 | 页面按 `coverUrl` 和 `failedImageIds` 局部降级，商品正文独立渲染 | `CatalogFlowIntegrationTest` 验证 MinIO 签名失败时媒体 URL 缺失但商品读取继续成功 | Playwright 向 Admin 浏览器提供真实商品 PNG 并验证两张图片可见；页面同时展示标题、品牌、分类和金额 |
| 员工会话不能接收旧投影 | entity 使用 `operatorId + accessToken + accessRevision` 判定响应归属 | entity 单测在请求未完成时切换 operator，断言旧结果不写入 | Playwright 通过真实管理员登录和路由守卫进入页面；浏览器控制台、页面异常和意外失败响应均为 0 |

浏览器证据运行真实 Chromium、Vue、Pinia、Foundation client 和 HTTP 请求。Catalog
接口使用受控 HTTP 服务以稳定复现分页、图片和 503；该证据用于验证前端请求和显示
语义，不替代数据库正确性。后端专项使用 H2 MySQL 兼容模式验证权限、状态、事务、
乐观锁、金额和媒体降级。本批没有修改后端，因此真实 MySQL、MinIO 与读副本事实继续
引用 [商品目录服务](10-catalog-service.md) 和
[M0–M8 三层审查](69-m0-m8-pre-m9-three-layer-audit-20260728.md)。

## 7. 最终门禁

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 45 / 45 |
| UI primitives | 5 / 5 |
| Storefront | 171 / 171 |
| Admin | 43 / 43 |
| 前端单元/契约测试合计 | 270 / 270 |
| 分层规则 | 24 / 24 |
| 分层文件 / 相对导入 | 135 / 249 |
| Playwright 全量 Mock E2E | 51 / 51 |
| V6.4 专项 Playwright | 10 / 10 |
| Catalog 后端专项 | 3 / 3 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

CSS 清理后的生产构建：

```text
Storefront CSS 101.19 kB / gzip 14.33 kB
Storefront JS  356.90 kB / gzip 107.63 kB
Admin CSS      49.23 kB / gzip 7.59 kB
Admin JS       267.67 kB / gzip 81.56 kB
```

## 8. 下一坐标

V6.4.3 下一最小切片应在 After-sale、Review、Chat 和管理首页中选择一个继续迁移。
Catalog 页面不再扩建管理写能力，本批也不进入 V7。
