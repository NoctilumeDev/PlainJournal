# 前端低耦合分层第三批：顾客会话与账户边界

> 完成日期：2026-07-30  
> 范围：顾客会话、游客袋下层状态、登录、注册、账户页、路由守卫和依赖门禁  
> 边界：不进入 M9，不改变 Identity/Trade 接口、令牌协议、购物袋合并语义或交易状态机

## 1. 结论

原 `stores/session.ts` 不能被简单改名为 Identity entity。它除了登录、刷新和退出，还在
会话建立后编排 Trade 游客袋合并，因此属于跨 Identity 与 Trade 的用户能力。第三批将
它归入 `features/customer-session`，并把纯本地游客袋状态下沉到
`entities/guest-bag`：

```text
foundation
    ↓
entities/guest-bag
    ↓
features/customer-session
    ↓
pages/identity + pages/account
    ↓
app/router + app/header
```

这一区分避免“目录看起来像 entity、实际却偷偷编排另一个领域”的伪分层。顾客会话继续
拥有登录、注册后建会话、刷新恢复、退出与合并状态；游客袋实体只持有设备商品、稳定合并
键和待确认所有者事实。

## 2. 代码边界

```text
storefront-web/src/
├── entities/guest-bag/
│   ├── model/guestBag.ts
│   └── index.ts
├── features/customer-session/
│   ├── model/session.ts
│   ├── ui/AuthenticationPanel.vue
│   └── index.ts
├── pages/
│   ├── identity/LoginPage.vue
│   ├── identity/RegisterPage.vue
│   └── account/AccountPage.vue
└── app/
    ├── AppHeader.vue
    └── router.ts
```

- 所有外部消费者只能通过 `features/customer-session/index.ts` 和
  `entities/guest-bag/index.ts` 使用能力；旧 `stores/session`、`stores/bag` 引用为 0。
- `AuthenticationPanel` 是登录与注册共用的交互积木，表单差异由显式 `mode` 表达；
  page 只读取安全 `returnTo` 并在会话真正建立后完成路由替换。
- 路由守卫和 Header 使用同一个会话公开入口，不各自解释刷新令牌。
- 账户页只编排账户事实和已有领域入口；账户样式跟随页面所有者，不再由全局 CSS
  隐式控制。
- `storefront.css` 从第二批的 2034 行收敛到 1857 行。

购物袋文件的迁移是建立正确依赖方向所需的结构调整，没有改变 add/update/remove、
稳定合并键、同账户重试、跨账户阻断或成功后按提交数量扣减的实现。

## 3. 高风险会话事实

| 边界 | 保持方式 |
| --- | --- |
| access token 生命周期 | 只保存在 Pinia 内存，不写入 localStorage |
| refresh token 恢复 | 只读取 `plain-journal:customer-refresh-token:v1`，成功刷新后原位轮换 |
| 过期 refresh 401 | 清除本地会话并静默进入登录页，不显示后端英文错误 |
| refresh 传输异常 | 不伪造退出或成功，保留 refresh token 与明确错误供重新加载恢复 |
| 并发 restore | 多个调用共享一次实际 refresh 工作，只产生 refresh + currentUser 两个请求 |
| 登录/注册完成点 | 取得 token、读取 currentUser 并完成既有游客袋合并后才通知页面跳转 |
| 游客袋结果未知 | 保留本地商品和原稳定请求键，不换键盲目重提 |
| 合并所有者 | 待确认请求绑定 userId，另一账户不能接管重放 |
| 服务端退出未知 | 保持当前会话，不宣称退出成功；只有用户明确选择才执行本地清除 |
| returnTo | 继续由 `safeReturnTo` 拒绝协议 URL、`//` 和反斜线路径 |

本批没有改变上述业务代码的决策，只改变归属、公开入口和页面组合；新增测试将原来仅靠
隐含行为保护的并发 restore、恢复传输异常和本地清除边界固定下来。

## 4. 三层验证

| 层级 | 证据 |
| --- | --- |
| 代码 | 会话归入 feature、游客袋归入 entity；登录/注册共用认证组件；三个页面归入 pages；路由、Header 和全部旧 store/view 通过公开入口使用；旧路径和外部深层导入为 0 |
| 自动化 | 7 个依赖规则测试通过，扫描 35 个分层文件和 173 条相对导入；Foundation 41、Storefront 59、Admin 12，共 112 个 Vitest；10 个 Playwright E2E；两端类型检查和生产构建通过 |
| 真实浏览器 | 桌面实际完成退出、登录、注册及商品详情安全回跳；账户展示 `2079000000000000999` 未丢精度；375×844 请求视口下客户区 360×844、账户页宽 340 px、标题和退出区纵向排列、水平溢出 0；正常旅程控制台 warn/error 为 0 |

自动化浏览器另外注入一次确定的过期 refresh token，直接观察 refresh HTTP 401、URL
回到 `/login?returnTo=/account`、本地凭据清除、页面无英文错误，再完成登录与退出。
Chrome 会为故意触发的 401 产生一条资源控制台提示；测试只豁免这条已由 URL 和 HTTP
状态精确约束的提示，其余控制台错误仍必须为 0，不能通过忽略所有错误得到通过。

真实浏览器资源清单观察到 101 项资源，其中包括：

- `features/customer-session/index.ts`、`model/session.ts` 和
  `ui/AuthenticationPanel.vue`；
- `entities/guest-bag/index.ts` 和 `model/guestBag.ts`；
- `pages/identity/LoginPage.vue`、`RegisterPage.vue` 与
  `pages/account/AccountPage.vue`；
- `/api/v1/identity/auth/register`、`/auth/login`、`/identity/me` 和账户购物车读取。

这证明新模块进入真实 Vite 运行图，注册顺序也确实为 register → login → currentUser，
不只是静态目录和测试替身。

本批浏览器使用受控 API 夹具验证前端会话、URL、交互、状态呈现、响应式布局和运行时
模块边界，不替代 Identity 的真实 MySQL、JWT、RBAC 与刷新令牌服务端证据。服务端
权限和令牌机制继续以 [Identity 安全设计](08-identity-security.md)及
[M0–M8 三层审查](69-m0-m8-pre-m9-three-layer-audit-20260728.md)为准；本批没有修改
后端。

## 5. 构建与视觉取舍

最终 Storefront 构建约为：

```text
CSS 48.57 kB / gzip 8.14 kB
JS  270.24 kB / gzip 83.80 kB
```

与第二批相比，CSS gzip 仅增加约 0.02 kB；认证组件与账户页 scoped 样式获得明确
所有权。页面继续使用同一青荷令牌、留白和细线关系，代码拆分没有在视觉上制造组件边框。

## 6. 运行环境收口

- Mock API、Vite 和 Playwright 结束后，18000、18090、18200、18201 及所有业务服务
  端口监听为 0，项目 Java/Node 进程为 0，本批手工浏览器日志目录已删除。
- Docker daemon 可读，但当前 7 个核心容器均为 stopped，另有一个正常退出的一次性
  RocketMQ 初始化容器；本批没有启动、重启或修改它们。七个核心容器
  `OOMKilled=false`，但这不足以解释它们为何停止。
- Broker 未运行，因此不能在线复核当前 RocketMQ 消费组。本批不把“无法查询”写成
  “临时消费组为 0”；M0–M8 的既有清理证据不受影响，下一次真实中间件验证应在按
  `docs/07` 恢复环境后重新查询。

## 7. 下一最小风险切片

下一批建议处理地址能力和账户子页面，而不是立即拆 Checkout：

1. 把 Identity 地址 API、地址状态和地址表单归入独立下层实体/feature；
2. 保留地址业务 ID string、默认地址唯一事实、服务端只读版本事实和删除确认，
   不虚构后端命令尚未提供的客户端乐观锁；
3. 账户页只通过公开入口组合地址页面，不读取其内部 store；
4. 使用代码、自动化、桌面/移动浏览器三层验证；
5. 地址稳定后，再单独进入购物袋页面和账户购物车边界。

Checkout、订单、Payment、售后和 M9 仍冻结。

该切片已完成，最终结构、协议边界和三层证据见
[前端低耦合分层第四批](74-frontend-address-layering-fourth-slice-20260730.md)。
