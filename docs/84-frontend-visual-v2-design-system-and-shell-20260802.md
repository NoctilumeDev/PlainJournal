# 前端视觉 V2：设计系统、Primitives 与全局壳层收口

> 完成日期：2026-08-02  
> 状态：V2 已完成，等待用户确认后进入 V3  
> 范围：两层设计令牌、无业务 UI primitives、顾客端/管理端全局壳层与登录入口  
> 硬边界：未修改 API、DTO、状态机、权限、幂等键、请求顺序或跨服务裁决权

## 1. 本批结论

V2 已把此前分散在两端全局 CSS 中的基础控件和壳层语法收敛为：

```text
design-system 基础令牌
        ↓
design-system 语义令牌
        ↓
@plain-journal/ui 无业务 primitives
        ↓
顾客端 / 管理端 app shell 与页面组合
```

青荷与素白只切换页面表面、品牌、文字、边界、媒体和焦点关系。成功、警告、错误、
处理中、结果未知、需要关注和已退款等风险语义跨主题保持稳定。本批没有用视觉调整
隐藏 `PROCESSING`、结果未知、权限拒绝或所有者域事实。

V2 退出门禁已满足：

- 顾客端和管理端直接声明并加载 `@plain-journal/ui`；
- 页面与业务组件不能直接使用基础 palette 或硬编码颜色；
- 两端共用容器、按钮、字段、状态提示、表面和行动组语法；
- 320px 根页面无横向溢出，管理端密集导航只在自身内部滚动；
- 键盘跳转链接、`focus-visible`、禁用/加载状态和 reduced-motion 成立；
- 两主题风险状态语义一致；
- 旧通用按钮、登录字段和壳层规则已随消费者迁移删除，没有保留两套实现。

## 2. 代码证明

### 2.1 两层令牌

`frontend/packages/design-system/src/tokens.css` 现在明确区分：

- 基础材料：palette、字体、间距、圆角、阴影、动效；
- 语义用途：surface、text、line、action、focus、media、status；
- 兼容别名：旧 `--pj-color-*` 暂时映射到语义令牌，供 V3–V6 按消费者删除。

三条令牌契约测试保证：

1. 原始色值只存在于 design-system；
2. 风险与生命周期状态不进入主题覆盖区；
3. 兼容别名只能映射到语义令牌。

### 2.2 无业务 UI 包

新增 `frontend/packages/ui`：

- `PjButton.vue`
- `PjField.vue`
- `PjStatusNotice.vue`
- `PjSurface.vue`
- `PjActionGroup.vue`
- `PjPageContainer.vue`

该包不依赖顾客会话、订单、支付、履约或管理端角色。四个组件测试覆盖按钮语义、
字段关联、状态提示和组合容器。两端登录入口、异步重试、Header、Footer、主容器和
管理端退出警告已经开始消费该包。

### 2.3 分层门禁

分层规则由 15 条增加到 16 条，新增约束包括：

- 两端应用必须直接声明和加载 `@plain-journal/ui`；
- UI 包不能依赖业务 foundation 或应用源码；
- design-system 之外禁止硬编码颜色和 `--pj-palette-*`；
- 页面不能绕过公开入口穿透 feature/entity 内部实现。

最终扫描 98 个分层文件和 206 条相对导入。

### 2.4 商品展示资产断链修复

真实浏览器验收发现首页引用：

- `/images/catalog/canvas-commuter-tote.png`
- `/images/catalog/mist-blue-notebook.png`

但 Vite `public` 目录中没有对应文件，浏览器只能显示替代文本。现已把此前已生成并
审阅的两张商品摄影资产放入
`frontend/storefront-web/public/images/catalog/`，不修改 Catalog DTO 或 Mock 商品
事实。V2 E2E 新增真实图片解码断言，并显式把两个原生懒加载图片滚动进加载范围后再
检查 `complete` 与 `naturalWidth`，避免把正常懒加载时序误判为失败。

## 3. 审查中发现并关闭的问题

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| 可选 props 类型失败 | `exactOptionalPropertyTypes` 下显式传入 `undefined` | 调用方只在有值时传递属性 |
| 主按钮对比度仅 2.61:1 | `:where()` 零特异性被基础 `button { color: inherit }` 覆盖 | 组件状态选择器改用 `:is()` |
| loading 恢复瞬间对比度 1.26:1 | 文字和背景同时进行颜色插值 | 按钮状态颜色即时切换，页面表面过渡保留 |
| 素白选中说明对比度 4.08:1 | 选中说明仍使用次级文字色 | 选中行说明改用主文字语义 |
| 管理端 320px 根宽度 439px | 隐式 grid `auto` 轨道被长履约号撑开 | `minmax(0, 1fr)`、`min-width: 0`、长标题换行 |
| 首页商品图只显示 alt | 引用路径存在但 `public` 资产缺失 | 补齐两张资产并增加懒加载解码断言 |

这些问题均由浏览器计算样式、DOM 尺寸或真实图片解码事实定位，不依赖应用日志猜测。

## 4. 自动化证据

环境：

- Node.js `D:\Node.js\current\node.exe`，24.14.0；
- pnpm 11.9.0；
- Windows PowerShell，测试、构建和浏览器工作区严格串行。

最终结果：

| 门禁 | 结果 |
| --- | ---: |
| 设计令牌契约 | 3 / 3 |
| Foundation | 42 / 42 |
| UI primitives | 4 / 4 |
| Storefront | 121 / 121 |
| Admin | 12 / 12 |
| 前端单元/契约测试合计 | 182 / 182 |
| 分层规则 | 16 / 16 |
| Playwright Mock E2E | 17 / 17 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |
| `git diff --check` | 通过 |
| Markdown 相对链接 | 88 份文档通过 |

Playwright 新增两类 V2 证据：

1. 顾客端 320px：默认青荷、素白切换、风险语义稳定、skip link、reduced-motion、
   无根溢出、axe 0，以及两张商品图完成真实解码；
2. 管理端 320px：履约与退货真实路由、单一 `main`、根页面无溢出、导航内部滚动和
   axe 0。

## 5. 内置浏览器与 F12 证据

本批只启动 Mock API、顾客端 Vite 和管理端 Vite，未启动 Docker 或完整中间件。
三项进程按顺序启动，验收结束后核对命令行并停止，18000、18200、18201 最终均释放。

### 顾客端

- 320px 登录页：单一 `main`，按钮最小高度 44px，页面内容宽度没有自身横向溢出；
- 390px 全局索引：青荷 `--pj-surface-page = #f2f7f6`，素白为 `#f7f7f5`；
- 两主题的 danger 均为 `#9a4f47`，result-unknown 均为 `#765f8b`；
- 1280px 首页：Header、main、Footer 各一份；
- 帆布通勤袋和青灰随行本均真实加载为 1122×1402，`complete = true`；
- Console warning/error：0。

### 管理端

- 320px `/fulfillment`：根 body 宽度与 scrollWidth 均为 320；
- `.admin-nav` 为 `clientWidth 320 / scrollWidth 672`，溢出被限制在导航自身；
- 两个代表 `.admin-work-card` 均为 300px，内部 scrollWidth 不超过自身；
- 页面标题为“履约与退货”，单一 `main`；
- Console warning/error：0。

内置浏览器的全页截图在固定元素与长页面拼接时会出现重复段落的工具侧拼接现象，
因此本批不以该截图作为宽度裁决；最终结论使用 DOM 实际矩形、scrollWidth、图片
naturalWidth、Playwright 断言和普通视口截图交叉确认。

## 6. 真实性边界

本批没有改变高风险业务判断和网络请求顺序，因此没有重新启动全套 MySQL、Redis、
Nacos、RocketMQ、MinIO 或真实八服务链。Mock API 只验证视觉壳层、交互语义、响应式
和浏览器状态，不替代 M0–M8 已封存的资金、库存、权益、权限和最终一致性真实证据。

如果 V3 修改订单状态映射、Payment 恢复入口、履约动作条件或权限降级，必须重新运行
对应真实链，不能引用本报告中的 Mock E2E 作为业务正确性证明。

## 7. 下一坐标

V2 正式关闭。下一阶段为 V3：

1. 首页；
2. 商品详情；
3. 订单详情；

三个真实路由先验证同一套视觉语言，再决定哪些页面模式上升为领域组件。V3 不在本批
自动开始，等待用户确认。
