# 前端视觉 V6.4.3：Chat 客服工作区

> 完成日期：2026-08-03  
> 状态：V6.4.3 Chat 切片已完成；V6 仅剩管理首页  
> 范围：客服队列、认领授权、消息历史、可靠回复、已读、关闭与实时提示  
> 硬边界：未修改 Chat API、数据库状态机、Outbox、WebSocket 路由、附件授权或恶意文件扫描规则

## 1. 核心结论

旧管理页面虽然复用了 Foundation Chat controller，但仍直接理解员工 session、认领、
成员权限、消息读取、15 秒轮询、WebSocket、发送 unknown、已读和关闭恢复。旧
`stores/chat.ts` 只是把这些依赖薄包装起来，无法阻止员工或 token 切换后的迟到结果
继续写入当前页面。

本批没有重写已经通过真实 MySQL、Redis、RocketMQ 和双实例验证的 Chat 协议，而是在
Foundation 上增加管理端所有者边界：

```text
Chat REST / WebSocket ticket
          ↑
Foundation Chat API + controller
          ↑
entities/admin-chat
  - operator/token workspace revision
  - 逐员工 pending send
  - 响应运行时合同
  - 认领与关闭恢复
  - 路由、轮询与 realtime 生命周期
          ↑
ChatWorkspaceView
  - 队列摘要
  - 连续会话事实
  - 操作与结果未知表达
```

页面只组合访问上下文和视图，不再从员工 profile 决定谁是消息发送者、会话是否属于
当前客服或旧请求能否提交。

## 2. 访问代次与内容隔离

每个有效访问上下文由三项组成：

```text
authorized
operatorId
accessToken
```

operator 或 token 任一变化都会：

1. 停止旧 workspace 的 WebSocket；
2. 增加 access revision；
3. 创建新的 reactive state、API 和 controller；
4. 让旧 REST、旧轮询、旧 WebSocket 回调只能写入已废弃对象；
5. 重新从当前员工自己的 pending key 恢复未确认回复。

pending key：

```text
plain-journal:admin-chat:pending-send:v2:{operatorId}
```

另一员工不会读取、显示、查询或重试原员工的消息正文与 `clientMessageId`。同一员工
token 轮换时也不会接收旧凭据请求的迟到历史。

## 3. 队列摘要与正文权限

客服队列 GET 可以返回开放会话的有限摘要，但单条会话、消息历史、回复和已读都要求
当前员工已经是 `conversation_member`。

页面激活未认领会话时：

```text
读取 OPEN 队列摘要
  -> 显示顾客、主题、认领状态
  -> 不调用 messages GET
  -> 不渲染正文或回复表单
```

认领事务在后端使用会话行锁，并写入 `assigned_agent_id`、成员事实和离线回执。认领
返回 5xx 时，页面不能仅凭按钮点击显示成功：

- 队列仍未分配：保持 unknown，正文继续隐藏，只能对同一会话重试；
- 队列显示其他客服：明确拒绝当前员工读取；
- 队列与单条会话都确认当前员工：才加载消息和推进已读。

因此“前端隐藏”不是授权本身，后端成员检查仍是最终裁决；前端只保证不在授权确认前
主动请求私聊正文。

## 4. 发送与关闭恢复

### 4.1 客服回复

首次发送前 Foundation 持久化：

```text
operatorId
conversationId
clientMessageId
content
createdAt
```

网络、超时、非法响应或 5xx 后，先查询 MySQL 历史中的原 `clientMessageId`：

- 已存在：按权威消息恢复；
- 不存在或查询失败：保持 unknown，冻结原键与正文；
- 重试：先再次查询，再以原键、原正文 POST；
- 明确 4xx：清除 pending，显示拒绝，不伪造未知成功。

当回复仍 unknown 时，页面和 entity 都禁止关闭会话。如果首次发送实际没有到达，而
会话先被关闭，原键将无法再通过同一状态机安全补写，因此该阻断属于一致性边界，不是
纯交互限制。

### 4.2 关闭会话

关闭没有额外命令 ID，但后端对同一成员、同一会话的 `CLOSED` 状态幂等。响应丢失后
Foundation 读取单条会话：

- 返回 `CLOSED`：采用权威状态，历史只读；
- 读取失败或仍非 `CLOSED`：保持 unknown；
- unknown 时只允许针对同一会话重试关闭。

页面不会用 OPEN 队列消失来猜测关闭成功，因为队列消失也可能由其他状态或查询范围
造成。

## 5. 运行时合同

`admin-chat` 对 Foundation API 返回增加运行时校验：

- conversation、customer、agent、message、sender、attachment 和 read ID 必须是
  十进制字符串，禁止 64 位雪花 ID 进入 JavaScript number；
- 会话状态只接受 `OPEN/CLOSED`，序号、未读数和 version 必须是非负整数；
- 消息必须属于请求会话，发送响应还必须匹配当前 operator 和原 `clientMessageId`；
- 历史消息 ID 与 sequence 不得重复，必须升序，游标与 `hasMore` 必须一致；
- 已读响应必须匹配请求会话和最后消息 ID；
- WebSocket ticket 只接受 `/ws/chat` 和 `ticket` 查询参数。

任何错会话、错发送者、错客户端键、重复序号、number ID 或错误票据路径的 2xx 都按
合同错误处理，不能写入当前工作区。

## 6. 视觉组合

页面使用 `PjPageContainer`、`PjSurface`、`PjField`、`PjButton`、
`PjActionGroup` 和 `PjStatusNotice`，按客服任务组织为：

```text
实时与权威边界
  -> OPEN 队列摘要
  -> 会话成员和未读事实
  -> 认领边界
  -> MySQL 历史
  -> 可靠回复或只读关闭状态
```

旧的服务分块和广告式大卡片没有被带入。消息仍保留对话方向，但背景、边界和动作层级
统一使用设计令牌。附件只展示已绑定元数据，并明确当前客服界面不开放下载；上传、
拖放和下载按钮仍为 0。

旧 `frontend/admin-web/src/stores/chat.ts` 已在零消费者确认后删除。旧
`.admin-chat-*` 全局 CSS 整段删除，Chat 页面样式归当前 composition 所有。

## 7. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实 Chromium 运行证据 |
| --- | --- | --- | --- |
| 认领前不读取正文 | 后端 `claimConversation` 行锁后写 agent member；`listMessages` 必须 `requireMember`；entity 未分配时只清空消息 | ChatFlowIntegrationTest 覆盖非成员拒绝和认领；entity 测试断言未认领只发队列 GET | 浏览器选择会话后正文为 0，Mock 诊断 `preClaimMessageReads=0`；认领权威确认后才出现历史 |
| 认领响应丢失不提前授权 | entity 只有在队列与单条会话确认 `assignedAgentId=operatorId` 后加载消息 | entity 模拟成员事务提交后 503，确认只发一次 claim 并从权威事实恢复 | Chromium 首次 claim 提交成员事实后返回 503，页面先经权威事实收敛，再开放正文 |
| 回复 unknown 不换键 | Foundation 保存 pending 并按 `clientMessageId` 查历史；entity 按 operator 分区存储 | entity 测试覆盖原键/正文两次完全一致、重启恢复和另一员工隔离 | F12 捕获两次 POST 的 `clientMessageId/content` 完全一致；权威记录只有一条客服消息、attempts=2 |
| unknown 回复阻断关闭 | entity 与关闭按钮都检查 pending send | entity 测试断言 pending 存在时 close 不发 HTTP | Chromium unknown 期间“结束会话”禁用，原键恢复后才允许关闭 |
| 关闭响应丢失只由 CLOSED 收敛 | 后端 CLOSED 幂等；Foundation 失败后读单条会话；guarded API 强制 close 响应为 CLOSED | entity 模拟提交 CLOSED 后 503，断言权威 GET 才 accepted | Chromium close 首次提交后 503，页面只在单条事实返回 CLOSED 后显示只读历史 |
| 员工/token 迟到响应隔离 | workspace revision、对象替换和旧 socket stop | entity 测试延迟 history，token 切换后旧消息不能进入新状态；pending key 按员工隔离 | 浏览器请求均使用当前员工 token；V6.3 既有浏览器用例继续证明另一账户 unresolved 正文 DOM 为 0 |
| 64 位与目标身份合同 | guarded API 对所有 ID、conversationId、senderId、clientMessageId、游标和票据运行时校验 | entity 使用 19 位字符串并拒绝 number ID；Foundation Chat 契约继续通过 | 浏览器 URL、DOM、请求和 Mock 权威记录逐字符使用 19 位字符串 ID |
| WebSocket 不伪造最终事实 | ticket 单次消费、socket 代次和 REST/MySQL 历史独立 | Foundation、Chat ticket/handshake、M8 Chat 与 entity 测试覆盖连接和恢复 | Chromium socket 在线时发送仍走 REST；历史刷新后恢复；真实双实例链继续引用 M8 文档 |

受控 Mock 只用于稳定注入三种浏览器故障：

```text
claim 事务已提交 -> 503
send 请求未到达 -> 503
close 状态已提交 -> 503
```

它记录 F12 实际请求和服务端权威内存事实，但不替代真实中间件。Chat 的真实 MySQL
事务、并发幂等、Redis 单次票据、RocketMQ、双实例实时路由、MinIO、ClamAV 和权限
证据继续引用第 56–62、68、69 号文档。

## 8. 最终门禁

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 45 / 45 |
| UI primitives | 5 / 5 |
| Storefront | 171 / 171 |
| Admin | 68 / 68 |
| 前端单元/契约测试合计 | 295 / 295 |
| Chat entity 专项 | 8 / 8 |
| 分层规则 | 27 / 27 |
| 分层文件 / 相对导入 | 144 / 254 |
| Playwright 全量 Mock E2E | 57 / 57 |
| V6.4 专项 Playwright | 16 / 16 |
| M8 Chat 浏览器专项 | 3 / 3 |
| platform-common | 21 / 21 |
| chat-service | 59 / 59 |
| 类型检查、生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 101.19 kB / gzip 14.33 kB
Storefront JS  356.90 kB / gzip 107.63 kB
Admin CSS      62.71 kB / gzip 8.93 kB
Admin JS       316.61 kB / gzip 94.10 kB
```

全量 Chromium 首轮出现一次旧 V5.2 测试在 `/index` 等待主题控件超时。失败截图为空白，
没有应用 DOM，也没有指向 Chat 的请求或异常。没有把 56/57 当作成功，而是继续执行：

1. 读取失败截图与 Playwright error context；
2. 单独串行重跑 V5.2，2/2 通过；
3. 再次全量串行重跑，57/57 通过。

这项过程保留在文档中，避免用“偶发”跳过证据，也避免把无代码依赖的空白页超时误判
为 Chat 回归。

## 9. 清理与下一坐标

测试结束后 `18090/18200/18201` 均由脚本释放。零消费者确认结果：

- 旧 `stores/chat.ts`：已删除；
- 旧 `.admin-chat-*` 全局 CSS：已删除；
- 页面直接导入 admin-chat model：0；
- admin-chat entity 导入 legacy session store：0；
- 管理 Chat 上传/下载按钮：0。

V6 仅剩管理首页。本批不进入首页，不进入 V7，也不启动 Docker 或全套中间件。
