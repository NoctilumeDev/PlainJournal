# 前端视觉 V6.3：通知与 Chat 收口

> 完成日期：2026-08-03  
> 状态：V6.3 已完成；下一批为 V6.4 管理端  
> 范围：通知中心、顾客 Chat、所有者隔离、结果未知、实时连接与附件边界  
> 硬边界：未修改 Chat/Notification 状态机、消息确认语义、附件授权或管理补偿权限

## 1. 本批结论

V6.3 没有把通知和 Chat 合并成一个“消息中心”大 store。两者仍是不同事实域：

```text
Notification entity ──> NotificationCenterPage

Chat entity ──> SupportChatView

customer-session ──只提供当前 owner/token──> page composition
```

通知是可分页、可已读的站内事实；Chat 是带会话成员、消息顺序、客户端幂等键、已读
回执和实时连接的工作区。页面共享设计令牌和 primitives，但不共享状态机。

视觉迁移同时关闭三项不能由样式遮盖的高风险边界：

1. Notification 雪花 ID 不再以 JSON number 进入浏览器，避免超过 JavaScript 安全整数
   后发生精度丢失；
2. 通知已读写入的网络、超时、非法响应和 5xx 保持 unknown，必须通过权威列表 GET
   核对，不能直接变成“已读成功”；
3. Chat 在账户或 token 切换后创建全新 workspace，旧请求只能修改已经废弃的旧状态，
   另一账户的 pending 正文、主题和重试键不能进入当前页面。

## 2. Notification 边界

### 2.1 Foundation 与 entity

新增独立 Foundation Notification API：

- `notifications(cursor, size)`；
- `unreadCount()`；
- `markRead(notificationId)`；
- `saveEmailPreference(email, enabled)`。

通知 entity 显式接收 authenticated、ownerId、accessToken 和 revision。owner 改变时
立即清空列表、游标、错误和 pending read；同 owner token 轮换时废弃旧请求。列表响应
提交前还必须满足：

- 当前 owner/token/revision 未改变；
- `items`、`nextCursor` 和 `hasMore` 契约有效；
- 每个通知 ID 是非空字符串；
- keyset `nextCursor` 只作为不透明字符串保存和回传，浏览器不解码、不重写。

### 2.2 已读结果未知

已读写入的展示语义：

| 情况 | 页面语义 | 恢复方式 |
| --- | --- | --- |
| 明确 2xx | success | 当前列表将目标通知改为 READ |
| 明确 4xx | warning | 保持原事实，提示当前动作未成立 |
| 网络、超时、非法响应或 5xx | unknown | 保持 UNREAD，记录目标 ID，只允许权威列表 GET 核对 |
| 权威 GET 仍失败 | unknown | 不清除 pending，不显示成功 |
| 权威 GET 返回 READ | success | 清除 pending，采用服务端事实 |
| 权威 GET 仍返回 UNREAD | warning | 清除 unknown，说明服务端未确认已读 |

恢复动作不会自动重放 POST。这样即使服务端已经提交但响应丢失，也不会通过无界重试
制造新的歧义。

### 2.3 64 位 ID 契约

以下后端响应字段使用 Jackson `ToStringSerializer`：

- `NotificationView.id`；
- `EmailPreferenceView.userId`；
- `EmailDeliveryAttempt.deliveryId`；
- `DeliveryRetryView.deliveryId`。

Notification 集成测试断言列表 JSON 中的通知 ID 是字符串。真实验证脚本还增加了
MySQL 与 Gateway 的逐字符比较：

```text
MySQL CAST(in_app_notification.id AS CHAR)
        ↓
Gateway GET /api/v1/notifications?size=20
        ↓
JSON 节点必须是 string
        ↓
Ordinal exact match
```

2026-08-03 的真实值为：

```text
mysqlId   = 2084092130033098754
gatewayId = 2084092130033098754
jsonType  = string
exactMatch = true
```

该值远高于 JavaScript `Number.MAX_SAFE_INTEGER`，因此这不是形式性注解，而是实际精度
边界。

### 2.4 邮件偏好没有伪造读取事实

后端当前提供邮件偏好 PUT，但没有当前偏好的 GET。V6.3 没有在页面上伪造“默认关闭”
或从空状态猜测当前设置，因此邮件偏好暂不进入通知中心。待后端提供权威读取契约后，
再设计可编辑页面。

## 3. Chat 边界

### 3.1 独立 workspace

旧顶层 `stores/chat.ts` 已删除，Chat 迁入独立 entity。每个 access context 持有自己的
workspace 对象；owner 或 token 改变时创建新对象，旧异步请求即使返回也只能写入旧
对象。

提交到当前页面前校验：

- `conversation.customerId` 必须等于当前 owner；
- 会话、消息、附件、已读回执和 WebSocket ticket 结构有效；
- ticket 只接受 `/ws/chat` 和 `ticket` 查询参数；
- 另一账户的 unresolved message 不得按 ID、正文、主题或重试键合并进当前工作区。

### 3.2 发送结果未知与幂等键

`clientMessageId` 在首次发送、响应未知和顾客重试之间保持不变。页面不会因为网络错误
重新生成键，也不会把 unknown 画成发送成功。

```text
首次发送 ──同一 clientMessageId──> 结果未知
   │
   └────────同一 clientMessageId──> 查询/重试恢复
```

另一账户登录后不会显示旧账户 pending 消息的正文；这是内容隔离，不只是把按钮禁用。

### 3.3 实时连接不是最终事实

WebSocket 只负责提示新事实可能到达。REST/MySQL 历史仍是权威来源：

- WebSocket 中断显示“实时连接中断”，不改写历史消息状态；
- 已读失败单独显示，不和发送失败合并；
- 发送响应未知使用 unknown；
- 附件隔离使用 attention；
- 页面可通过 REST 重读恢复，不要求 WebSocket 自己证明最终消息事实。

附件上传和下载入口继续关闭。页面只展示已有附件的隔离、扫描或授权边界，不把尚未
完成的顾客端附件能力伪装成可用功能。

## 4. 视觉迁移

通知中心新增正式账户路由 `/account/notifications`，并接入账户首页和全局索引。通知
按连续事实行展示标题、内容、引用、时间和已读状态，不使用广告式消息卡片。

Notification 与 Chat 页面均迁移到：

- `PjPageContainer`；
- `PjSurface`；
- `PjButton`；
- `PjActionGroup`；
- `PjStatusNotice`；
- 共享状态 tone 与两主题语义。

`SupportChatView` 不再依赖旧 Chat 全局 CSS。会话列表、历史、当前操作和异常反馈仍按
同一顾客旅程排列，但代码上的 owner-domain 隔离没有被视觉组合破坏。

## 5. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实运行 / 浏览器证据 |
| --- | --- | --- | --- |
| Notification 64 位 ID | 四个响应字段使用 `ToStringSerializer`；前端只接受 string ID | Notification 集成测试与前端契约测试拒绝 number ID | 真实 MySQL/Gateway 返回同一 `2084092130033098754`，JSON 类型为 string |
| 通知 owner/token 隔离 | access revision 与 owner/token 双重校验，切换时清空状态 | 单测让账户 A 迟到、B 先完成，A 被 `NotificationAccessChangedError` 拒绝 | V6.3 Chrome 只显示当前 owner 通知；账户切换后旧响应不进入 DOM |
| 已读结果未知 | POST 失败保持 UNREAD 和 pending ID；恢复只执行列表 GET | entity 测试覆盖 GET 确认 READ、GET 仍不可用和无自动重提 | Playwright 丢弃已读响应后页面保持 unknown，权威列表 GET 后才收敛 |
| 不透明游标 | entity 只保存并原样回传 `nextCursor` | Foundation/entity 测试覆盖后续页与终止页 | 浏览器请求使用服务端游标，页面不解码或拼装业务字段 |
| Chat owner 内容隔离 | workspace 随 access context 重建；校验 `customerId` | 单测让 A 的 pending 慢返回，B 工作区不含 A 正文和键 | V6.3 Chrome 断言另一账户 unresolved message 内容在 DOM 中为 0 |
| Chat 发送结果未知 | `clientMessageId` 跨 unknown/retry 保持稳定 | Foundation、entity 与既有 M8 测试覆盖同键恢复 | V6.3 Chrome 捕获同一重试键；既有真实 Chat 浏览器链证明 MySQL 幂等收敛 |
| WebSocket 与 REST 权威分工 | ticket 校验与实时连接独立于历史读取 | entity、M8 Chat 与 V6.3 测试分别覆盖 ticket、断连和重读 | 既有真实双实例 WebSocket/Redis/RocketMQ 链证明实时投递；页面断连不伪造历史失败 |
| 附件隔离 | 页面无上传/下载动作，只显示附件治理事实 | Chat 测试覆盖 attachment 契约与 quarantine 文案 | V6.3 Chrome 显示隔离状态；真实 MinIO/ClamAV/授权证据见第 58、62、69 号文档 |

V6.3 Playwright 使用受控 HTTP 与真实 Chrome 验证页面状态、请求次数、owner 切换、
两主题、移动端、无障碍和 Console。它不替代真实中间件。Chat 的 MySQL、Redis、
RocketMQ、WebSocket ticket、MinIO 和 ClamAV 事实继续引用第 57–62、69 号文档；
Notification 本批因修改响应契约，已重新执行真实 MySQL、RocketMQ、Gateway 和 SMTP
验证。

## 6. Notification 真实链

执行：

```powershell
pwsh -File .\backend\tools\verify-m8-notification-delivery.ps1
```

完整路径：

```text
Payment Outbox
  -> RocketMQ
  -> Notification 幂等消费
  -> 站内信 UNREAD
  -> SMTP 两次失败
  -> NEEDS_ATTENTION
  -> 顾客恢复 403
  -> 管理员同命令幂等恢复
  -> 稳定 Message-ID 发送一次
  -> 毒消息治理且 Actuator 不暴露原文
```

真实结果：

| 验证项 | 结果 |
| --- | ---: |
| Gateway 通知列表 | HTTP 200 |
| MySQL/Gateway ID | string，逐字符一致 |
| SMTP 不可用后的尝试次数 | 2 |
| 失败状态 | `NEEDS_ATTENTION` |
| 顾客补偿 | HTTP 403 |
| 管理员首次/重复补偿 | HTTP 200 / 200 |
| 补偿审计 | 1 行 |
| 最终发送 | `SENT`，捕获 1 封 |
| 毒消息 | `NEEDS_ATTENTION` |
| Actuator 原始载荷泄漏 | false |

验证中发现并修复了脚本对调用者当前目录的依赖：Maven 现在显式使用
`backend/pom.xml`。同时修复 Gateway query 字符串的 PowerShell 插值边界。两次失败
批次都在重跑前确认临时 Topic、消费组、数据库数据、受管端口和项目 JVM 为零。

最终独立清理核查：

- Notification 六类 run-scoped 表计数均为 0；
- Payment Outbox run-scoped 行数为 0；
- 临时 RocketMQ Topic 和消费组无匹配；
- `18000/18105/18109/12525/18200/18201` 无监听；
- 项目 Java、Vite 和 Mock API 进程为 0；
- 七个核心容器和 Docker Desktop 已停止。

证据目录：

```text
backend/.run/m8-notification-20260803-093938
backend/.run/frontend-v63-final-check-20260803-094613
```

## 7. 自动化门禁

后端定向门禁：

| 模块 | 测试 |
| --- | ---: |
| platform-common | 21 / 21 |
| notification-service | 12 / 12 |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 |

最终 `pnpm check`：

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 45 / 45 |
| UI primitives | 5 / 5 |
| Storefront | 171 / 171 |
| Admin | 12 / 12 |
| 前端单元/契约测试合计 | 239 / 239 |
| 分层规则 | 19 / 19 |
| 分层文件 / 相对导入 | 120 / 238 |
| Playwright 全量 Mock E2E | 41 / 41 |
| V6.3 专项 Playwright | 3 / 3 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

运行时：

```text
Node.js 24.14.0
pnpm 11.9.0
```

生产构建：

```text
Storefront CSS 101.19 kB / gzip 14.33 kB
Storefront JS  356.90 kB / gzip 107.63 kB
Admin CSS      28.61 kB / gzip 5.51 kB
Admin JS      196.19 kB / gzip 63.46 kB
```

## 8. 浏览器与清理证据

V6.3 专项 Playwright 使用同机 Chrome、单 worker 串行运行：

- 通知已读响应丢失后保持 unknown 和 UNREAD；
- 权威通知列表返回 READ 后才清除 unknown；
- Chat 实时中断、已读失败、附件隔离和发送结果未知使用四种独立语义；
- 发送 unknown 与重试使用同一 `clientMessageId`；
- 另一账户的 pending 正文、主题和重试键不进入当前页面；
- 青荷与素白风险语义一致；
- 320、390px 无根页面横向溢出；
- axe serious/critical 为 0；
- Console warning/error 为 0。

清理结果：

- 旧顶层 Chat store 不存在；
- 旧 Chat 全局 CSS 已删除；
- Notification 与 Chat entity 均有独立分层规则；
- 页面不直接依赖 Foundation DTO 或 customer-session 内部状态；
- 专项与完整浏览器脚本结束后 `18090/18200/18201` 无监听。

## 9. 下一坐标

V6.4 只处理管理端：

1. 先盘点管理端导航、表格、筛选、分页、状态、治理命令和审计反馈；
2. 选择一个高密度治理页面作为管理端视觉原型，再扩散到其他页面；
3. 管理补偿必须保持角色、原因、幂等键和追加审计，不能被通用“成功按钮”弱化；
4. 不在 V6.4 顺带进入 V7 发布收口或未来《素简记 Pro》。
