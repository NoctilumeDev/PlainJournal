# M8 第一批：可靠聊天持久化与客户端幂等

> 状态：已完成  
> 完成日期：2026-07-23  
> 阶段边界：只完成 `STORED` 事实闭环；不宣称 WebSocket、多节点在线路由或整个 M8 完成

## 1. 本批目标

M8 需要同时覆盖聊天、通知、物流 GEO、评价、搜索和运营只读统计，但总计划要求
一个批次只承担一个主工作流。本批选择顾客与平台客服聊天作为 M8 主入口，先解决
实时系统最基础且不可跳过的问题：

- 发送方收到成功前，消息必须已经写入 MySQL；
- 消息与待投递事件必须在同一个本地事务内提交；
- 客户端断线或超时后的顺序重发、并发重发不能产生重复消息；
- 客服认领、会话访问、历史分页和已读未读必须有明确所有权；
- 实时基础设施尚未接入时，系统只能返回 `STORED`，不能伪造“已送达”。

## 2. 实现范围

新增 `chat-service`，默认端口 `18108`，Gateway 增加
`/api/v1/chat/** -> lb://chat-service` 路由。`deploy/docker/bootstrap-resources.ps1`
新增独立 `ecom_chat` schema、最小权限账号和 Nacos `chat-service.yml`。

首批 API：

| API | 语义 |
| --- | --- |
| `POST /api/v1/chat/conversations` | 以顾客和 `clientConversationId` 幂等创建会话 |
| `GET /api/v1/chat/conversations` | 顾客查看自己的会话；客服查看开放队列 |
| `GET /api/v1/chat/conversations/{id}` | 只允许会话成员读取 |
| `POST /api/v1/chat/conversations/{id}/claim` | `ADMIN/OPERATOR` 认领未分配会话 |
| `POST /api/v1/chat/conversations/{id}/messages` | 消息持久化并返回 `STORED` |
| `GET /api/v1/chat/conversations/{id}/messages` | 基于 `message_sequence` 的 keyset 历史分页 |
| `POST /api/v1/chat/conversations/{id}/read` | 单调推进成员已读位置并更新回执 |

MySQL 表：

- `chat_conversation`：会话所有者、客服分配、业务上下文引用和最后消息序号；
- `conversation_member`：成员资格、角色和单调已读位置；
- `chat_message`：消息正文、客户端消息 ID、请求哈希、会话内序号和 `STORED` 状态；
- `message_receipt`：接收方 `OFFLINE/READ` 回执事实；
- `outbox_event`：`ChatMessageStored` 待发布事件；
- `chat_attachment`：M8.1 只预留所有权结构；M8.3 已扩展该表并开放上传确认、消息
  绑定和授权下载 API。

## 3. 一致性与并发裁决

发送事务按以下顺序执行：

```text
锁定 chat_conversation
  -> 核对成员资格
  -> 按 conversation + sender + clientMessageId 查询已有事实
  -> 同键同载荷返回原消息
  -> 同键不同载荷返回 409
  -> 分配 message_sequence
  -> 写 chat_message
  -> 写当前接收成员 OFFLINE 回执
  -> 写 ChatMessageStored Outbox
  -> 事务提交
  -> 返回 STORED
```

数据库唯一约束
`(conversation_id, sender_id, client_message_id)` 是最终幂等裁决；会话行锁使同一会话
的消息序号严格串行，唯一约束
`(conversation_id, message_sequence)` 防止重复序号。客户端正文只保存在
`chat_message`，Outbox 只携带路由所需 ID、序号、发送方、类型和状态，不复制私聊
正文。

客服认领同样锁定会话。首个客服写入 `assigned_agent_id` 和成员关系；相同客服重试
返回原事实，其他客服得到 `CONVERSATION_ALREADY_ASSIGNED`。客服认领前的顾客消息
补建 `OFFLINE` 回执，后续可以从数据库恢复，而不依赖 Redis。

## 4. 自动化证据

命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn -pl services/chat-service -am test
mvn -pl services/chat-service -am org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
```

结果：

- `platform-common` 14 个测试通过；
- `chat-service` 3 个测试通过；
- 0 失败、0 错误、0 跳过；
- M8.1 合入后全量 `mvn clean verify` 为 68 份 Surefire 报告、251 tests，
  12 个 Reactor 模块全部成功；
- 全 Reactor PMD 0 违规；
- Chat SpotBugs 4 条 Priority 2、Priority 1 为 0。

覆盖点包括：

- 会话创建顺序重试和请求哈希冲突；
- 8 路并发创建同一会话，最终仅一条会话和一个顾客成员；
- 消息顺序重试和同键不同正文冲突；
- 16 路并发发送同一客户端消息，最终仅一条消息和一条 Outbox；
- 越权访问拒绝、客服认领竞争、历史 keyset 分页、双方未读和已读回执。

## 5. 真实 MySQL、Nacos 与 Gateway 证据

验证脚本：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
./tools/verify-m8-chat-persistence.ps1
```

最终证据：

```text
backend/.run/m8-chat-persistence-m8chat20260723144605/verification.json
```

结果：

- Gateway 经 Nacos 将 `/api/v1/chat/**` 路由到真实 Chat JVM；
- 真实 MySQL 中形成 1 条会话、2 条消息、2 条 `PENDING` Outbox；
- 顾客重复发送返回同一消息 ID，同键不同正文返回 HTTP 409；
- 客服认领、双方已读完成，2 条回执均为 `READ`，顾客未读数归零；
- Outbox 中私聊正文命中数为 0；
- 验证结束后 Chat 五类业务事实行合计为 0；
- 18000/18108 监听和 PlainJournal Chat/Gateway JVM 残留均为 0。

本轮既有网络预检显示 Docker、容器出站、七个核心中间件、Maven 直连和单默认路由
正常，但当前 Clash 代理路径自身不可用。验证不需要代理或镜像拉取，因此在完成这次
等价人工检查后使用 `-SkipNetworkPreflight` 继续；没有修改代理、路由、网卡跃点、
Docker 数据或全局镜像源。

## 6. 当时未完成与后续状态

本报告保留 M8.1 完成时的历史边界。以下项目已经在 M8.2 完成：

- Outbox 发布器、RocketMQ 生产/消费和失败恢复；
- WebSocket JWT 鉴权、心跳、多个 Chat 节点的在线用户路由；
- Redis presence、节点租约、路由 TTL 和节点退出转离线；
- 消息 `DISPATCHED/DELIVERED/READ` 与回执 `OFFLINE/DELIVERED/READ` 状态推进；
- 真实双 Chat 实例、Gateway、MySQL、Redis、Nacos 和 RocketMQ 故障验证。

M8.2 的实现、证据和边界见
[M8 第二批：聊天实时路由、跨节点投递与离线回放](57-m8-chat-realtime-routing.md)。

以下项目已经在 M8.3 完成：

- 幂等上传意图和私有 MinIO 签名 PUT；
- 大小、MIME、文件头和完整 SHA-256 确认；
- 附件与消息本地事务原子绑定；
- 会话成员授权下载、下载前完整性复核和过期孤儿对象清理。

M8.3 的实现、证据和边界见
[M8 第三批：聊天附件存储、完整性与授权下载](58-m8-chat-attachment-storage-and-authorization.md)。

其后 M8.4 已完成浏览器短期、单次 WebSocket 握手票据，详见
[M8 第四批：浏览器 WebSocket 短期握手票据](59-m8-chat-browser-websocket-ticket.md)。

当前仍未完成：

- 独立恶意文件扫描与隔离区；
- Chat 专属持久化消费失败台账；
- Notification、物流 GEO、评价、搜索和运营统计；
- Chat 前端会话工作区。

M8.3 合入时的全量门禁为 74 份 Surefire 报告、263 tests，12 个 Reactor 模块
成功，0 失败、0 错误、0 跳过；M8.4 合入后的当前门禁为 76 份 Surefire 报告、
274 tests，全 Reactor PMD 0 违规。
