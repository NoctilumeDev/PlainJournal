# 前端视觉 V6.2：地址与优惠权益收口

> 完成日期：2026-08-02  
> 状态：V6.2 已完成；下一批为 V6.3 通知与 Chat  
> 范围：收货地址、地址写入未知结果、优惠权益、权益 owner/token 隔离与旧样式清理  
> 硬边界：未修改 Identity/Marketing API、地址版本协议、后端状态机或数据库事实

## 1. 本批结论

V6.2 没有把地址与权益合成一个账户大 store，而是保持两个所有者域事实：

```text
Identity Address entity ──> AddressManagementPage

Marketing Benefit entity ──> BenefitCenterView

customer-session ──提供当前 owner/token──> page composition
```

地址 entity 原有 owner/token 代次、服务端版本事实和写入后重读逻辑继续保留。权益旧
`stores/benefits` 被迁入 `entities/benefit`，不再反向读取会话 feature。两个页面只在
composition 层组合当前会话与对应 entity。

视觉迁移同时关闭两项不能由样式遮盖的缺陷：

1. 地址写入 5xx、断连、超时或非法响应现在明确为 unknown，先重读再决定是否重试；
2. 权益迟到响应不能在账户切换后写入，跨 owner 的 `userId` 响应会被拒绝展示。

## 2. 地址未知结果边界

### 2.1 错误语义

`addressStore` 新增三种展示语义：

| 情况 | tone | 含义 |
| --- | --- | --- |
| 读取失败或明确 4xx | danger | 当前动作没有完成或当前事实无法读取 |
| 写入网络/超时/非法响应/5xx | unknown | 服务端可能已经提交，不能直接重复 |
| 写入已确认但随后列表 GET 失败 | attention | 修改已成立，但页面列表可能过时 |

unknown 文案固定要求“先重新读取地址，核对事实后再决定是否重试”。页面保留原表单和
删除确认，不显示 success，也不自动重提。

### 2.2 不伪造幂等或版本能力

当前 Identity 地址命令没有 `Idempotency-Key`，`AddressInput` 也没有
`expectedVersion`。因此本批没有：

- 给创建地址伪造一个只有前端知道的幂等键；
- 在 PUT body 中增加后端未声明的 `version`；
- 把服务端返回的 version 宣传为客户端乐观锁；
- 在响应未知后自动重复 POST/PUT/DELETE。

恢复方式只有权威 GET。页面重读成功后提示顾客核对最新地址事实，仍由顾客判断是否
需要再次提交。

## 3. 权益所有者隔离

旧 benefit store 直接读取全局 session，并在异步响应后覆盖共享数组。账户 A 的慢
请求如果晚于账户 B 的登录完成，存在把 A 的权益提交到 B 页面状态的窗口。

新的 benefit entity：

- 通过 `BenefitAccessContext` 显式接收 authenticated、ownerId 和 accessToken；
- owner 改变时立即清空旧权益、loading 和 error；
- 同 owner token 轮换时保留已确认事实，但使旧请求代次失效；
- GET 只有在 owner、token、access revision 和 load revision 均一致时才能提交；
- 每条 Benefit 的 `userId` 必须等于当前 owner，否则拒绝整批响应；
- entity 不导入 customer-session，页面只能通过公开 `entities/benefit/index.ts` 使用。

浏览器整页跳转触发 refresh token 轮换后，权益请求实际使用
`browser-*-access-token-rotated`。这证明 entity 使用的是当前刷新后的 token，而不是
登录时缓存的旧 token。

## 4. 权益生命周期语义

Marketing 当前用户权益只有：

```text
AVAILABLE -> LOCKED -> REDEEMED
                   \-> AVAILABLE（订单取消或关闭后释放）
```

页面删除了并不属于 `user_benefit` 的 RELEASED、EXPIRED 映射，避免把 pricing lock
状态误当成用户权益状态。最终视觉语义：

| Benefit 状态 | tone | 顾客解释 |
| --- | --- | --- |
| `AVAILABLE` | success | 满足门槛与地区条件时可用于结算 |
| `LOCKED` | processing | 已为订单保留，等待订单结果 |
| `REDEEMED` | neutral | 已由明确订单使用，不能再次结算 |
| 未识别值 | attention | 保留服务端原值，要求以订单与结算事实为准 |

LOCKED 没有被画成“优惠成功”，REDEEMED 也没有继续使用可用态颜色。

## 5. 视觉迁移

地址页面现在使用：

- `PjPageContainer`；
- `PjSurface`；
- `PjField`；
- `PjButton`；
- `PjActionGroup`；
- `PjStatusNotice`。

页面不再自建内容宽度，不消费 `--pj-color-*` 兼容令牌，也不依赖
`catalog-header`、`content-path`、`eyebrow`、`form-error`、`primary-action` 或
`inline-confirmation`。

权益中心从两列卡片改为连续事实行。金额、门槛、生命周期、有效期、规则、订单和地区
资格按同一阅读顺序呈现，不再使用 `benefit-card` 或 `order-status-badge`。

## 6. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实浏览器证据 |
| --- | --- | --- | --- |
| 地址写入响应未知 | `mutationResultMayBeUnknown` 只将网络、超时、非法响应与 5xx 映射 unknown；page 不在 catch 中重提 | address entity 与 page test 断言 unknown、表单保留、无 success | Chrome 先让 Mock 真正提交 POST，再把浏览器响应改成 503；页面保持 unknown，POST 只有一次 |
| 地址权威重读 | 页面恢复动作只调用 `addresses.load()`，不重放 mutation | page test 让第二次 GET 返回已提交地址 | Chrome 点击“重新读取地址”后看到已提交地址，unknown 消失，create request 数量仍为 1 |
| 地址版本协议 | update 继续发送原 AddressInput，不增加 version | entity 与 M4/V6.2 测试断言 PUT body 无 version | Chrome 捕获真实 PUT body 不含 version，服务端返回后的地址事实正常更新 |
| 默认地址与删除回补 | page 只接受 entity 在 Identity 确认并重读后的列表 | 既有 address store、M4 与 V6.2 测试覆盖设默认、取消删除、确认删除 | Chrome 中默认标签移动到新地址，删除后原地址重新成为默认 |
| 权益迟到 owner 响应 | benefit entity 使用 owner/token/access/load revision | 单测让 A 延迟、B 先完成，再断言 A 被 `BenefitAccessChangedError` 拒绝 | Chrome 退出 A、登录 B 后只显示 `BEN-SECOND`，A 的三条权益为 0 |
| 权益响应 owner 校验 | 提交前逐条检查 `benefit.userId === ownerId` | entity 与 view test 注入 foreign owner，断言列表为空和 danger | 浏览器 owner 切换请求分别携带当前轮换 token；页面只展示匹配 owner 的响应 |
| 权益生命周期 | `statusPresentation` 只识别 AVAILABLE/LOCKED/REDEEMED | view test 断言 success/processing/neutral 且无旧徽章 | Chrome 三态同时可见，LOCKED 与 AVAILABLE 不共用颜色；两主题含义一致 |

本批 Chrome 使用受控 HTTP Mock 验证页面请求、响应丢失、DOM、owner 切换、主题、
无障碍和 Console。它不替代真实 MySQL。Identity 地址 CRUD、JWT/RBAC、Marketing
权益状态和跨服务释放/核销的真实证据继续引用第 15、35、36、69 号文档；本批没有修改
这些后端实现。

## 7. 自动化门禁

针对性验证：

```text
address entity + benefit entity + 两个页面    14 / 14
V6.2 专项 Playwright                           3 / 3
```

最终 `pnpm check`：

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 42 / 42 |
| UI primitives | 5 / 5 |
| Storefront | 163 / 163 |
| Admin | 12 / 12 |
| 前端单元/契约测试合计 | 228 / 228 |
| 分层规则 | 17 / 17 |
| 分层文件 / 相对导入 | 113 / 232 |
| Playwright 全量 Mock E2E | 38 / 38 |
| V6.2 专项 Playwright | 3 / 3 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 97.82 kB / gzip 14.00 kB
Storefront JS  338.27 kB / gzip 103.20 kB
Admin CSS      28.61 kB / gzip 5.51 kB
Admin JS      196.19 kB / gzip 63.46 kB
```

## 8. 浏览器与清理证据

V6.2 专项 Playwright 使用同机 Chrome、单 worker 串行运行：

- 地址新增、修改、设默认、删除取消、确认删除和默认回补完成；
- committed POST 的浏览器响应被替换为 503 后，页面没有 success 或自动重提；
- 权威 GET 后读到 Recovery Address，create 始终只有一次；
- customer A 的 AVAILABLE/LOCKED/REDEEMED 与 customer B 的权益完全隔离；
- 青荷与素白的生命周期语义一致；
- 320、390、1280px 无根页面横向溢出；
- axe serious/critical 为 0；
- 非注入 503 的 Console warning/error 为 0。

清理结果：

- `stores/benefits.ts` 与旧测试路径不存在；
- `benefit-card`、`benefit-list` 旧全局卡片规则已删除；
- `order-status-badge` 四条旧全局规则已删除；
- BenefitCenter 的 RELEASED、EXPIRED 死映射已删除；
- 旧类名只在“必须不存在”的回归断言中保留，不是运行时消费者。

其他页面仍在使用的 `form-error`、`eyebrow`、`content-path` 和兼容令牌继续保留，等待
V6.3/V6.4 按消费者迁移，未做跨批次清理。

本批没有启动 Docker、Java 或完整中间件。专项与完整浏览器脚本结束后应保持 18090、
18200、18201 无残留监听。

## 9. 下一坐标

V6.3 只处理通知与 Chat：

1. 先审查 notification 与 chat 的 owner、游标、WebSocket ticket、附件隔离和已读语义；
2. 通知与聊天保持两套 owner domain，不合并成一个“消息中心”状态机；
3. 连接中断、发送结果未知、已读失败与附件隔离必须保持不同语义；
4. 不在 V6.3 顺带进入管理端治理页面。
