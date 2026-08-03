# 前端低耦合分层第一批

> 完成日期：2026-07-28
> 范围：设计系统归属、顾客端应用壳层、导航、主题 feature、依赖方向门禁
> 边界：不进入 M9，不改变接口、交易语义、权限事实或后端状态机

## 1. 结论

第一批采用“代码边界强、视觉边界弱、状态边界明确、用户旅程连续”的原则。分层用于
限制依赖和状态所有权，不把页面切成一排排有边框的组件，也不引入微前端、模块联邦、
全局事件总线或第二套状态管理方案。

装配方向为：

```text
design-system / foundation
          ↓
        shared
          ↓
       entities
          ↓
       features
          ↓
        pages
          ↓
         app
```

源码依赖方向与装配方向相反：上层可以组合下层，下层不能反向导入上层。同一 feature
内部可以协作，跨 feature 只能通过公开 `index.ts`，不能伸入另一个 feature 的
`model` 或 `ui` 内部。

## 2. 审计发现

| 发现 | 风险 | 第一批处理 |
| --- | --- | --- |
| `foundation` 同时输出跨应用 API 契约和视觉 CSS | 服务契约与品牌视觉同生命周期发布 | 新建独立 `@plain-journal/design-system`，两端直接声明依赖 |
| 路由、Pinia 装配、Header 和主题 store 平铺在 `src` | 应用装配与业务状态没有清楚边界 | 路由、Pinia、Header、Footer、Shell 归入 `app`；主题归入 `features/theme` |
| 主题视图直接持有主题状态与完整模板 | 页面难以复用或独立测试主题能力 | 提取 `ThemePreference` 和公开 feature 入口 |
| `storefront.css` 同时持有壳层、主题和全部页面样式 | 任一局部调整可能影响全站 | 壳层和主题样式跟随所有者组件；其余页面样式暂留，后续按领域迁移 |
| 目录约定只能靠记忆 | 后续很容易重新形成反向依赖 | 新增可执行依赖门禁及 5 条规则测试，并纳入 `pnpm check` |

审计同时确认，不能把剩余大文件机械按行拆开：

- `OrderDetailView.vue` 约 951 个物理行，直接组合 5 个领域 store，是当前顾客端最明显
  的页面编排热点；
- `stores/checkout.ts` 约 504 个物理行，并组合购物车、地址和会话状态，属于高风险
  交易工作流，迁移时必须保留稳定幂等键与结果未知恢复；
- `foundation/chat.ts` 约 1241 个物理行，包含 Chat 契约、HTTP 客户端和实时能力，
  后续应按协议职责拆分，不能按方法数量随意切；
- 第一批移出壳层与主题样式后，`storefront.css` 仍约 2372 个物理行；
- 管理端 `admin.css` 约 961 个物理行，`FulfillmentWorkspaceView.vue` 约 582 个物理
  行，适合在顾客端模式稳定后再迁移。

## 3. 第一批代码边界

```text
frontend/
├── packages/
│   ├── design-system/       品牌令牌与浏览器基础样式
│   └── foundation/          API 契约、领域 DTO、格式化与客户端
└── storefront-web/src/
    ├── app/                 启动装配、路由、全局壳层与跨域导航
    ├── features/theme/      主题状态、主题选择界面和公开入口
    ├── shared/lib/          无领域归属的纯函数
    ├── api/                 迁移中的旧 API 适配层
    ├── components/          迁移中的旧通用组件
    ├── stores/              迁移中的旧领域状态
    └── views/               迁移中的旧页面编排
```

`api/components/stores/views` 是显式迁移缝，不冒充已经完成的分层。当前规则允许
`app` 暂时组合旧目录，但禁止 `shared/entities/features/pages` 反向依赖旧目录。
这使迁移可以逐页推进，又不会让新代码继续扩大旧耦合。

`features/theme/index.ts` 是主题能力的唯一外部入口。主题内部 UI 可以读取自身 model，
页面和启动入口不能直接导入 `features/theme/model`。视觉令牌只由
`@plain-journal/design-system` 输出；`foundation` 不再输出 CSS。

## 4. 三层验证

| 层级 | 证据 |
| --- | --- |
| 代码 | 独立 `design-system`；`app` 壳层；`features/theme` 公开入口；`shared/lib` 纯导航函数；Header、Footer、主题样式跟随所有者组件；旧全局选择器无残留 |
| 自动化 | 5 个 Node 分层规则测试通过；实际扫描 11 个分层文件和 165 条相对导入；Foundation 41、Storefront 53、Admin 12，共 106 个 Vitest；7 个 Playwright E2E；两端类型检查与生产构建通过 |
| 真实浏览器 | 桌面首页为青荷，计算令牌 `#F2F7F6/#1E2725/#4F6A67`；Header/Footer 同宽 1233 px，页面无水平溢出；全局索引切换素白后得到 `#F7F7F5/#667064`，刷新仍恢复素白，再恢复青荷；请求 375×844 移动视口时文档客户区为 360×844，Header/Footer 均 340 px、导航右边界 350 px、水平溢出为 0；全过程控制台 warn/error 为 0 |

浏览器页面资源清单共观察到 83 项资源，其中包括运行时实际加载的
`packages/design-system/src/tokens.css`、`base.css`、`src/app/router.ts`、
`AppShell.vue`、`AppHeader.vue`、`AppFooter.vue`、`features/theme/index.ts` 和
`ThemePreference.vue`。这证明新边界进入真实 Vite 模块图，不只是一组静态目录。

本批浏览器仍使用受控 API 夹具，只验证前端结构、路由、主题、渲染与交互。交易、
资金、库存、权益和权限的真实中间件结论继续以 M0–M8 三层审查为准，本批没有改动
这些边界。

## 5. 后续最小风险顺序

1. 迁移 Catalog 浏览链：商品实体、商品卡片、列表与搜索 feature。它可建立
   `entities/pages` 范例，且不触碰交易状态机。
2. 迁移 Identity 会话边界和账户页；将“会话事实”作为其他 feature 的显式输入，
   不允许每个 feature 自行恢复或解释身份。
3. 单独处理购物袋与 Checkout。必须逐项保留游客袋合并键、下单稳定键、固定请求
   快照和结果未知查询恢复，每个结论继续要求代码、自动化和真实浏览器三层。
4. 再拆 Order Detail，将支付、履约、售后和评价作为页面编排下的 feature，不改变
   用户看到的一条连续订单旅程。
5. 顾客端模式稳定后迁移 Chat 和管理端；最后再按所有者拆分剩余全局 CSS。

任何一批都不得以“新建了目录”作为完成标准。只有依赖门禁、原有测试、真实浏览器
和视觉连续性同时通过，才算完成。

## 6. 运行时边界

本机当前 `PATH` 中：

```text
node = D:\Node.js\current\node.exe
Node.js = 24.14.0
pnpm = 11.9.0
```

`D:\Node.js\node.exe` 仍是旧 18.20.8，不应直接硬编码调用。项目统一使用 `PATH` 或
`D:\Node.js\current`，这样新旧运行时不会争抢同一环境变量。本批未修改机器级环境
变量。

验证结束后 18090/18200 监听和本批 Mock API/Vite 进程均已停止；七个核心
PlainJournal 中间件容器保持原状。

## 7. 后续记录

上述数字是第一批完成时的历史证据，不随以后批次覆盖。Catalog 公开浏览链已在第二批
迁入 `entities/pages`，当前代码门禁和三层证据见
[前端低耦合分层第二批](72-frontend-catalog-layering-second-slice-20260728.md)。
