# M8 第三批：聊天附件存储、完整性与授权下载

> 日期：2026-07-23  
> 阶段边界：完成 M8.3 附件存储与授权闭环；不宣称恶意文件扫描、浏览器聊天工作台、Notification 或整个 M8 完成

## 1. 本批目标

M8.1 已保证消息事实可靠落库，M8.2 已完成多节点实时投递。M8.3 只处理一个
独立问题：图片或文件不能依靠客户端提供的 MinIO 对象键直接写进消息，也不能仅凭
对象键下载。

本批建立以下闭环：

```text
会话成员创建幂等上传意图
  -> 使用短期 PUT URL 上传到私有 MinIO Bucket
  -> Chat 校验大小、MIME、文件头和完整 SHA-256
  -> 上传意图进入 READY
  -> 发送消息事务原子写 chat_message + chat_attachment
  -> 上传意图进入 ATTACHED
  -> 会话成员请求短期 GET URL
  -> Chat 重新校验对象 SHA-256 后签名下载
```

MySQL 保存上传意图、确认元数据、附件归属、清理状态和消息绑定事实；MinIO 保存
二进制对象。对象键不会进入 Outbox，Redis 和 RocketMQ 也不保存附件正文。

## 2. 数据模型

新增 `chat_attachment_upload`：

- `conversation_id + uploader_id + client_upload_id` 唯一；
- 保存请求哈希、环境隔离对象键、原始文件名、声明 MIME 和大小；
- 确认后保存实际 MIME、实际大小和完整 SHA-256；
- 绑定后保存 `message_id`；
- 保存孤儿清理抢占、尝试、错误和完成时间。

扩展 `chat_attachment`：

- 每个对象只能绑定一个上传意图；
- 每个消息内使用 `sort_order` 保存稳定顺序；
- 保存对象键、文件名、MIME、大小和确认时 SHA-256；
- 通过 `message_id` 归属消息，再由消息归属会话。

上传状态：

```text
PENDING -> READY -> ATTACHED
    |         |
    +---------+-> CLEANING -> DELETED
                    |
                    +-> CLEANUP_PENDING -> CLEANING
```

- `PENDING`：上传意图存在，对象尚未确认；
- `READY`：对象元数据、文件头和 SHA-256 已确认；
- `ATTACHED`：已在消息本地事务中绑定，清理任务不能删除；
- `CLEANING`：过期未绑定对象已由一个实例抢占；
- `CLEANUP_PENDING`：MinIO 删除失败，保留错误并等待重试；
- `DELETED`：对象删除成功，MySQL 保留可审计状态。

陈旧 `CLEANING` 超过恢复窗口后允许其他实例重新抢占。MinIO 删除是幂等动作，
MySQL 状态更新是最终裁决。

## 3. API

创建上传意图：

```http
POST /api/v1/chat/conversations/{conversationId}/attachments/upload-intents
```

请求包含：

- `clientUploadId`
- `fileName`
- `mimeType`
- `sizeBytes`

相同用户、会话和 `clientUploadId` 的相同请求返回同一上传事实；请求内容变化返回
`IDEMPOTENCY_CONFLICT`。

确认上传：

```http
POST /api/v1/chat/conversations/{conversationId}/attachments/{uploadId}/confirm
```

确认不持有会话行锁调用 MinIO。Chat 先读取上传意图，再检查真实对象，最后在短
MySQL 事务内把 `PENDING` 推进为 `READY`。

发送附件消息：

```http
POST /api/v1/chat/conversations/{conversationId}/messages
```

消息类型支持：

- `TEXT`：必须有正文，不能携带附件；
- `IMAGE`：必须携带 1 至 5 个已确认图片；
- `FILE`：必须携带 1 至 5 个已确认允许文件。

消息事务锁定会话和上传意图，分配消息序号，写入消息与附件，并把上传意图推进为
`ATTACHED`。同一上传不能绑定第二条消息。

授权下载：

```http
GET /api/v1/chat/conversations/{conversationId}/messages/{messageId}/attachments/{attachmentId}/download
```

只有会话成员可以获得短期 GET URL。签名前重新读取完整对象并核对 MIME、大小、
文件头和 SHA-256；对象被覆盖时返回 `ATTACHMENT_OBJECT_MISMATCH`，不能返回伪成功
URL。

## 4. 文件准入

首版允许：

| MIME | 扩展名 |
| --- | --- |
| `image/jpeg` | `.jpg`、`.jpeg` |
| `image/png` | `.png` |
| `image/webp` | `.webp` |
| `application/pdf` | `.pdf` |
| `text/plain` | `.txt` |

单文件默认不超过 10 MB，最多 5 个附件。确认阶段同时检查：

- 声明大小与 MinIO 实际大小完全一致；
- 声明 MIME 与对象 MIME 一致；
- JPEG、PNG、WebP、PDF 文件头匹配；
- 文本前缀不包含 NUL；
- 完整对象 SHA-256 已保存。

这属于类型和完整性准入，不冒充恶意文件扫描。生产环境若允许来自不可信来源的
复杂文档，仍需在 `READY` 前接入独立恶意文件扫描器和隔离区。

## 5. 一致性与失败语义

| 故障 | 行为 |
| --- | --- |
| MinIO 无法签发上传 URL | 上传意图保持 `PENDING`，返回 503，可按同一幂等键重试 |
| 对象尚未上传 | 确认返回 409，不能写 `READY` |
| MIME、大小、文件头不符 | 返回 422，上传意图保持 `PENDING` |
| 同一上传绑定第二条消息 | 返回 409，不新增消息或附件 |
| 非会话成员请求下载 | 返回 403，不签发 URL |
| 已确认对象被同尺寸覆盖 | 下载前 SHA-256 不符，返回 422 |
| 过期孤儿删除失败 | 状态进入 `CLEANUP_PENDING`，保留错误并重试 |
| 清理实例在删除前退出 | 陈旧 `CLEANING` 超时后可由其他实例重新抢占 |

对象确认不在持有会话行锁时访问 MinIO；消息绑定事务也不调用 MinIO。外部存储慢或
故障不会扩大消息事务锁时间。

## 6. 自动化门禁

针对性命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn -pl services/chat-service -am test
mvn -pl services/chat-service -am org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
```

结果：

- `platform-common`：14 tests；
- `chat-service`：15 tests；
- 0 失败、0 错误、0 跳过；
- PMD 0 违规。

新增覆盖：

- 上传意图 8 路并发重试收敛为一个 MySQL 事实；
- 对象缺失、文件头不符和存储不可用不伪造成功；
- 消息与附件原子绑定、重复消息返回同一事实；
- 同一上传禁止绑定第二条消息；
- 历史 REST 与 WebSocket 帧包含附件元数据；
- 非成员下载拒绝；
- 同尺寸对象覆盖被 SHA-256 复核拦截；
- 孤儿清理成功、失败转 `CLEANUP_PENDING` 和恢复重试。

最终全 Reactor：

```powershell
mvn clean verify
```

结果为 74 份 Surefire 报告、263 tests，12 个 Reactor 模块成功，0 失败、0 错误、
0 跳过。

最终静态门禁：

```powershell
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
mvn -pl services/chat-service `
  com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs `
  '-Dspotbugs.effort=Max' `
  '-Dspotbugs.threshold=Low' `
  -DskipTests
```

全 Reactor PMD 0 违规。Chat SpotBugs 低阈值专项为 32 条诊断：Priority 1 为 0、
Priority 2 为 23、Priority 3 为 9；23 条 Priority 2 均为 Spring 单例构造注入
依赖的 `EI_EXPOSE_REP2`，没有对外返回这些引用。请求附件 ID 列表已使用不可变复制，
不存在 `EI_EXPOSE_REP`。

## 7. 真实 MySQL、MinIO 与 Gateway 证据

命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
./tools/verify-m8-chat-attachments.ps1
```

权威证据：

```text
backend/.run/m8-chat-attachments-m8attd42436c3d59b/verification.json
```

关键结果：

```json
{
  "gatewayStatus": "routed",
  "upload": {
    "replayReturnedSameUpload": true,
    "missingObjectStatus": 409,
    "confirmedStatus": "READY",
    "environmentScopedObjectKey": true
  },
  "message": {
    "replayReturnedSameMessage": true,
    "reuseStatus": 409,
    "attachmentCount": 1
  },
  "authorization": {
    "nonMemberDownloadStatus": 403,
    "memberDownloadBytesMatched": true
  },
  "integrity": {
    "checksumSnapshotsMatched": true,
    "tamperedObjectDownloadStatus": 422,
    "restoredObjectDownloadAllowed": true
  },
  "orphanCleanup": {
    "finalStatus": "DELETED",
    "cleanupAttempts": 1,
    "attachedObjectRemained": true
  },
  "privacy": {
    "objectKeyInOutbox": 0
  },
  "cleanup": {
    "mysqlRows": 0,
    "minioObjects": 0
  }
}
```

真实运行使用 Gateway、Nacos、MySQL 和私有 MinIO Bucket。验证脚本先通过完整网络
预检，不修改代理、路由、网卡或 Docker；结束后业务行、运行前缀对象、端口和受管
JVM 均无残留。

## 8. M8.3 结论与后续边界

M8.3 已关闭附件存储与授权主链：

- 幂等上传意图；
- 私有签名上传；
- 类型、大小、文件头和完整 SHA-256 确认；
- 消息事务内唯一绑定；
- REST 历史与 WebSocket 元数据；
- 授权下载和下载前完整性复核；
- 多实例安全的过期孤儿清理。

其后 M8.4 已完成浏览器原生 WebSocket 的短期、单次握手票据：

- 长期 JWT 只用于受保护 REST 换票；
- Redis 以票据 SHA-256 摘要建键，Lua 原子 `GET + DEL` 保证跨实例单次消费；
- 票据绑定环境、用户、角色、路径和过期时间；
- Redis 不可用时签发与握手均失败关闭；
- 原有 Authorization Header 客户端保持兼容。

详见
[M8 第四批：浏览器 WebSocket 短期握手票据](59-m8-chat-browser-websocket-ticket.md)。

以下是本批交付时尚未完成、现已由后续批次关闭的历史清单：

- 独立恶意文件扫描与隔离区；
- Chat 持久化 `consumer_failure` 台账；
- Chat 顾客端与客服工作台；
- Notification、物流 GEO、评价、搜索和运营统计。

本批交付时 M8.1–M8.4 已完成，整体 M8 仍在进行。2026-07-25 当前状态以
[M8 全面审查、回归与毕业收口](68-m8-full-audit-and-graduation-20260725.md)
为准：M8.1–M8.12 已完成并关闭。
