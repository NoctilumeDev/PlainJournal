# 前端视觉 V6.1：登录、注册与账户首页收口

> 完成日期：2026-08-02  
> 状态：V6.1 已完成；下一批为 V6.2 地址与优惠权益  
> 范围：顾客登录、注册、账户首页、游客袋合并反馈与退出结果未知  
> 硬边界：未修改 API、DTO、路由守卫、会话状态机、刷新令牌轮换、合并键或所有者隔离

## 1. 本批结论

V6.1 将认证与账户入口迁移到 V2 建立的统一视觉语法，没有重写已经成立的会话分层：

```text
entities/guest-bag
    ↓
features/customer-session
    ↓
pages/identity + pages/account
    ↓
app/router
```

登录和注册继续等待 access token、当前用户资料与游客袋合并流程结束后才发出完成事件。
账户首页按顾客任务组织地址、订单、售后、权益、支持、购物车与结算入口，内部服务名
不再成为主视觉文案。访问令牌仍只在内存，刷新令牌仍按既有恢复与轮换规则处理。

## 2. 不可变业务边界

本批保持以下行为不变：

- `safeReturnTo` 继续拒绝外部协议、双斜线与反斜线路径；
- 登录顺序仍为 login -> current user -> guest bag merge；
- 注册顺序仍为 register -> login -> current user -> guest bag merge；
- restore 并发仍合并为一次刷新轮换；
- 过期 refresh 401 才静默清理，传输未知继续保留刷新凭据；
- 游客袋合并继续使用稳定 owner、载荷与幂等键；
- 服务端退出成功前不清除本机会话；
- “仅清除此设备”仍必须由顾客明确触发。

`session.ts`、Foundation Identity/Trade API、路由守卫和游客袋实体均未修改。

## 3. 视觉与状态迁移

### 3.1 登录与注册

`AuthenticationPanel.vue` 改用：

- `PjPageContainer`；
- `PjSurface`；
- `PjField`；
- `PjButton`；
- `PjStatusNotice`。

旧 `form-error` 被 danger + assertive 状态提示取代。认证页不再自行建立内容宽度，也不
消费兼容颜色令牌或旧全局 `eyebrow` 类。登录失败与注册失败均不会发出
`authenticated`。

### 3.2 账户首页

`AccountPage.vue` 使用正式容器、表面、按钮和状态提示。账户资料仍显示邮箱、账户 ID
和身份，但 `ACTIVE`、`CUSTOMER` 等实现值分别以“使用中”“顾客”呈现；原始账户 ID
继续保留，便于事实追溯。

游客袋合并状态固定映射为：

| 会话事实 | 视觉语义 | 页面标题 |
| --- | --- | --- |
| `pending` | processing | 正在合并购物袋 |
| `succeeded` | success | 购物袋已合并 |
| `unknown` | unknown | 合并结果待确认 |
| `failed` | danger | 购物袋合并未完成 |
| `ownership-conflict` | attention | 需要先核对设备上的待确认合并 |

只有 `unknown` 和 `failed` 提供“使用原重试键再次确认”。ownership conflict 不会绕过
另一账户仍未确认的设备事实。

服务端退出 503 使用 unknown，不再被普通 warning 淹没。页面继续保留当前账户和刷新
凭据，同时提供明确的 destructive 本地清除动作；页面不会自动替顾客执行该动作。

## 4. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实浏览器证据 |
| --- | --- | --- | --- |
| 登录完成顺序 | `AuthenticationPanel` 只在 `session.login()` 完成后 emit；store 的 `establish()` 先写 token、再读 profile、再合并游客袋 | 认证组件与 session 测试覆盖 login、profile、merge | Chrome 捕获 POST login 后 GET me，随后才进入 `/account` |
| 注册完成顺序 | `registerAndLogin()` 固定 register -> login -> establish | 组件测试断言三次请求及载荷；失败时不 emit | Chrome 捕获 POST register、POST login、GET me 后才进入商品安全回跳 |
| 安全回跳 | 登录/注册页面继续使用 `safeReturnTo` | 既有导航契约测试与 V6.1 E2E | `returnTo=//evil.example` 被收敛到 `/account`，合法商品路径正常恢复 |
| 合并结果未知 | session 对网络、超时、非法响应和 5xx 映射 unknown；页面用 unknown notice | session 测试覆盖传输未知、5xx、迟到 owner 响应；账户组件覆盖五态 | Chrome 首次 merge 503 后保留本地商品与 pending key，DOM 无 success |
| 原键恢复 | `prepareMerge()` 在同 owner 待确认时返回原 pending；页面只调用既有 `mergeGuestBag()` | guest bag 与 session 测试覆盖稳定请求；账户组件限制恢复入口 | Chrome 两次 POST 的幂等键和载荷完全一致，第二次 200 后本地 pending 才清除 |
| 退出结果未知 | `logout()` 失败只写 `logoutError`，不调用 `clearSession()` | session 与账户组件测试断言 503 后仍认证、refresh 仍存在 | Chrome POST logout 503 后仍停在 `/account`；点击本地清除前 token 未删除 |
| 本地明确清除 | `clearLocal()` 仅由 destructive 按钮触发 `clearLocalOnly()` | 组件测试先断言保留，再触发按钮并断言清除 | Chrome 点击“仅清除此设备”后才返回首页并移除 refresh token |

本批浏览器运行使用受控 HTTP Mock 验证请求、DOM、响应式、主题、无障碍和错误恢复；
它不替代真实 Identity、Trade 与数据库事实。由于本批未修改后端协议或会话状态机，
服务端真实 MySQL、所有者隔离与跨服务证据继续引用第 34、69 号文档。

## 5. 自动化门禁

针对性验证：

```text
AuthenticationPanel + AccountPage + session    21 / 21
V6.1 专项 Playwright                            4 / 4
```

最终 `pnpm check`：

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 42 / 42 |
| UI primitives | 5 / 5 |
| Storefront | 155 / 155 |
| Admin | 12 / 12 |
| 前端单元/契约测试合计 | 220 / 220 |
| 分层规则 | 16 / 16 |
| 分层文件 / 相对导入 | 109 / 228 |
| Playwright 全量 Mock E2E | 35 / 35 |
| V6.1 专项 Playwright | 4 / 4 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 96.45 kB / gzip 13.92 kB
Storefront JS  332.75 kB / gzip 102.21 kB
Admin CSS      28.61 kB / gzip 5.51 kB
Admin JS      196.19 kB / gzip 63.46 kB
```

## 6. 浏览器与请求取证

V6.1 专项 Playwright 使用本机 Chrome、单 worker 串行运行，实际验证：

- 青荷登录页与素白注册页使用同一布局和语义状态；
- 非安全回跳不会离开本站；
- 注册、登录与 profile 请求顺序正确；
- 账户资料、顾客入口和状态文案不暴露 Trade/Marketing 服务名；
- merge 503 使用 unknown，重试继续使用原幂等键与原载荷；
- logout 503 不导航、不清令牌、不伪造退出成功；
- 本地清除只有明确点击后发生；
- 320、390、1280px 根页面无横向溢出；
- axe serious/critical 为 0；
- 非预期 Console warning/error 为 0。

本批没有把 Codex 内置浏览器对 localhost 的客户端拦截写成页面证据；可见页面、请求与
Console 证据来自同机 Chrome Playwright。

## 7. 清理与下一坐标

本批移除了登录/账户页面对以下旧语法的依赖：

- `form-error`；
- 旧全局 `eyebrow`；
- `account-notice` / `account-notice--warning`；
- 页面自建 `--pj-content-width` 宽度；
- `--pj-color-*` 兼容颜色令牌；
- 面向顾客的 Trade、Marketing 服务名。

全局 `form-error` 与 `eyebrow` 仍有其他未迁移消费者，因此没有越界删除。V6.2 应迁移
地址与优惠权益，并在消费者归零后再删除对应旧全局规则与 `order-status-badge`。

本批没有启动 Docker、Java 或完整中间件。专项和全量浏览器脚本均已结束，18090、
18200、18201 应保持无残留监听。下一批不得顺带进入通知、Chat 或管理端。
