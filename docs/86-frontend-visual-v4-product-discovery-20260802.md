# 前端视觉 V4：商品发现链收口

> 完成日期：2026-08-02  
> 状态：V4 已完成，停在 V5 之前等待用户确认  
> 范围：商品目录、搜索、全局索引、商品卡/网格、分页、图片 URL 状态与公开评价  
> 硬边界：未修改后端 API、DTO、权限、状态机、幂等键或所有者域裁决权

## 1. 本批结论

V4 已把商品发现链迁移到统一视觉语言，并保留所有可恢复事实：

```text
分类与分页
  -> 搜索与降级来源
  -> 全局索引
  -> 商品详情图片状态
  -> 公开评价
```

页面不使用促销入口、虚假“本周新入”或待运营占位内容制造商城氛围。分类、关键词、
页码和当前图片继续由 URL 表达；刷新、复制链接和前进后退不会丢失状态。搜索投影
降级为 MySQL 基础匹配时，页面明确显示 `MYSQL_FALLBACK`，不把召回范围收窄伪装成
正常搜索。

V4 退出门禁已经满足：

- 商品目录和搜索固定每页 12 件；
- 第一页链接省略 `page=1`，非法页码按第一页请求但不擅自重写用户 URL；
- 切换分类和提交新关键词会清除旧分页；
- 目录与搜索商品标题使用 `h2`，首页商品卡继续使用 `h3`；
- 全局索引只保留真实可用入口；
- 图片选择保存在 `image` 查询参数中，并保留其他查询参数；
- 320px、390px 和桌面代表宽度无根页面横向溢出；
- axe serious / critical 为 0，Console warning/error 为 0。

## 2. 代码证明

### 2.1 URL 与分页事实

`frontend/storefront-web/src/shared/lib/pagination.ts` 集中处理页码解析、总页数和查询
参数生成。`CatalogPagination.vue` 只负责分页语义和链接，不持有目录或搜索状态。

`ProductListPage.vue` 和 `SearchPage.vue` 从路由读取页码并生成可复制链接。分类切换
只保留分类事实，搜索提交只保留新关键词；两者都不会把旧页码带入新结果集。

### 2.2 搜索降级与商品语义

`SearchPage.vue` 持有服务端返回的搜索来源。`degraded=true` 时使用共享
`PjStatusNotice` 明确说明当前结果来自商品事实库基础匹配，排序和召回范围可能收窄。
警告状态仍是跨主题稳定语义，不随青荷/素白切换。

`ProductCard.vue` 与 `ProductGrid.vue` 接受页面传入的标题层级，避免首页、目录和搜索
为了复用卡片而破坏标题结构。

### 2.3 索引、图片与评价

`GlobalIndexView.vue` 删除不存在的运营入口，只保留商品分类、真实搜索、账户事务、
售后支持和主题选择。

`ProductDetailView.vue` 将当前图片编号保存在 `image` 查询参数中，图片切换时保留
`from` 等其他查询参数。商品详情继续读取 Catalog 公开评价，不改变点赞、举报和
登录返回路径。

### 2.4 浏览器夹具真实性

`frontend/e2e/mock-api.mjs` 的商品列表和搜索接口现在按 `page/size` 真实切片，不再
返回全部数据却伪装分页。评价夹具增加显式重置入口，使 V4 不受前序 M8 审核用例
隐藏种子评价的共享状态污染。

该夹具只验证前端契约和交互，不替代 Catalog 的真实 MySQL、OpenSearch、RocketMQ
或权限证据。

## 3. 审查中发现并关闭的问题

| 问题 | 证据 | 修复 |
| --- | --- | --- |
| Vite WebSocket 一次性 `ERR_NO_BUFFER_SPACE` | 故障后 30 分钟无新 Tcpip 4231/4266；UDP endpoint 30，TCP 状态低位，动态端口仍为 16384 | 不过滤错误、不改网络；环境恢复后重跑，业务断言正常 |
| 警告文字对比度只有 4.41:1 | axe 在搜索降级提示中报告 serious | `--pj-palette-amber-700` 调整为 `#80612a`，与表面约 5.10:1，并增加令牌对比度测试 |
| E2E 结束后偶发误判端口未释放 | 断言 3/3 已通过，现场只有 PID 0 的 `TIME_WAIT`，无监听进程 | 测试脚本和浏览器 fixture 使用最多 5 秒的有界监听释放检查 |
| M4 旧断言依赖连续文本 | 页面正确分层显示数字和单位，旧测试写死“1 件商品/匹配” | 改为对计数容器做结构化文本断言 |
| M8 审核状态污染后续 V4 | 全量顺序中种子评价已被设为 `HIDDEN`，V4 单独运行正常 | 增加评价 fixture 重置，V4 显式建立自己的前置事实 |
| 传统滚动条下 320px 全站横向溢出 | 内置浏览器 `clientWidth=305`、`scrollWidth=320`，页面出现水平滚动条 | 设计系统移除 `body min-width:20rem`，改为 `min-width:0` 并增加回归测试 |

最后一项只在真实内置浏览器的非覆盖式滚动条环境暴露；Playwright 无头浏览器使用
覆盖式滚动条，因此原有 320px 自动化没有发现。这证明响应式验收不能只依赖单一
浏览器实现。

## 4. 自动化证据

环境：

- Node.js `D:\Node.js\current\node.exe`，24.14.0；
- pnpm 11.9.0；
- Windows PowerShell；
- Mock API、Storefront、Admin 与 Playwright 严格串行启动和停止。

最终 `pnpm check` 结果：

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 5 / 5 |
| Foundation | 42 / 42 |
| UI primitives | 4 / 4 |
| Storefront | 133 / 133 |
| Admin | 12 / 12 |
| 前端单元/契约测试合计 | 196 / 196 |
| 分层规则 | 16 / 16 |
| 分层文件 / 相对导入 | 104 / 215 |
| Playwright 全量 Mock E2E | 23 / 23 |
| V4 专项 Playwright | 3 / 3 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

V4 专项请求取证包含：

```text
/api/v1/catalog/products?page=2&size=12&categoryId=2079000000000000101
/api/v1/catalog/search/products?q=通勤&page=2&size=12
/api/v1/catalog/search/products?q=书写&page=1&size=12
```

这三组请求分别证明分类与页码、搜索分页和新关键词回到第一页。测试同时捕获
`pageerror`、Console warning/error、URL、分页链接、标题层级、横向溢出和 axe。

## 5. 内置浏览器与 F12 证据

人工验收只启动 Mock API 18090 和 Storefront 18200，没有启动 Docker、Admin 或
完整中间件。检查过程与自动化门禁严格串行。

### 5.1 商品目录与搜索

- 实际点击“书写纸品”后，URL 变为 `/products?category=writing`，活动分类和商品
  同步切换；
- `/search?q=通勤` 恢复输入值和一件匹配商品；
- 提交新关键词后 URL 不携带旧 `page`；
- 搜索降级来源、分页链接和精确请求由 V4 Playwright 取证；
- 内置浏览器 Console warning/error 为 0。

### 5.2 全局索引与商品详情

- 全局索引只显示真实商品、账户、售后支持和主题入口；
- 不存在“内容待运营补齐”和虚假上新入口；
- 商品详情保留 `?image=1&from=index`；
- 商品主图可见，公开评价和评分汇总可见；
- 390px 下商品详情无横向溢出，Console warning/error 为 0。

### 5.3 真实滚动条响应式复验

首次 320px 验收发现全局水平滚动条：

```text
clientWidth = 305
scrollWidth = 320
```

修复设计系统基础样式后，商品目录、搜索和全局索引均为：

```text
clientWidth = 305
scrollWidth = 305
hasHorizontalOverflow = false
```

390px 商品详情为：

```text
clientWidth = 375
scrollWidth = 375
hasHorizontalOverflow = false
```

浏览器 Console 全程没有 warning/error。验收完成后 fixture 已停止，18090/18200
由完整 `pnpm check` 再次启动、测试和释放，最终无监听残留。

## 6. 真实性与回退边界

V4 修改的是前端展示、URL 恢复、共享语义样式和测试夹具，没有改变商品、评价或搜索
所有权。真实 Catalog 数据、搜索投影重建、评价治理、消息投递和权限仍引用 M8 与
M0–M8 三层证据。

本批可回退单位是商品发现链、共享分页组件、相关设计令牌和测试基础设施；不得回退
后端数据库迁移、状态机或用户已有未提交成果。禁止使用 `reset`、`checkout` 或
`clean`。

## 7. 下一坐标

V4 正式关闭。下一阶段是 V5 交易与售后链：

1. 购物袋与结算；
2. 订单列表与订单详情的批量迁移；
3. Payment、Fulfillment 与评价资格；
4. 售后申请、寄回、退款和结果未知；
5. 跨域事实连续呈现与高风险状态三层门禁。

V5 不在本批自动开始，等待用户确认。
