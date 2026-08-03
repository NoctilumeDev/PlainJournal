# M8 第四批：浏览器 WebSocket 短期握手票据

> 状态：已完成  
> 完成日期：2026-07-23  
> 阶段边界：只完成浏览器原生 WebSocket 的短期、单次握手认证；不宣称客服前端工作台、Chat 持久化消费失败治理、Notification 或整个 M8 完成

## 1. 本批目标

M8.2 已经支持携带 `Authorization` Header 的 WebSocket 客户端，但浏览器原生
`WebSocket` API 不能设置该 Header。把长期 JWT 直接放进查询参数会扩大浏览器历史、
代理、访问日志和重放窗口，因此 M8.4 增加一次短期换票：

```text
浏览器使用长期 JWT 调用受保护 REST API
  -> Chat 签发短期不透明票据
  -> 浏览器只把短期票据放入 /ws/chat 查询参数
  -> 任一 Chat 实例原子消费票据
  -> 握手成功后票据立即失效
```

本批不新增 Notification 或前端客服工作台，也不改变消息、回执和附件的 MySQL
事实所有权。

## 2. API 与浏览器流程

换票接口：

```http
POST /api/v1/chat/websocket-tickets
Authorization: Bearer <identity-jwt>
```

成功响应包含：

- `ticket`：默认 32 字节安全随机数生成的 43 字符 Base64URL 不透明值；
- `targetPath`：固定为 `/ws/chat`；
- `queryParameter`：固定为 `ticket`；
- `expiresAt`：服务端 UTC 过期时间。

响应强制：

```http
Cache-Control: no-store
Pragma: no-cache
```

浏览器随后连接：

```text
wss://<gateway>/ws/chat?ticket=<short-lived-ticket>
```

默认 TTL 为 30 秒，配置允许范围为 5 秒至 2 分钟。真实验证为降低本机调度抖动，
显式覆盖为 10 秒；这不是生产默认值。

## 3. 票据存储与单次消费

票据本身不是 JWT，不携带可由客户端解码的身份信息。Redis Key 使用原始票据的
SHA-256 摘要：

```text
ecommerce:{environment}:chat:ws-ticket:{sha256(ticket)}
```

Redis Value 保存受控身份快照：

- 环境命名空间；
- 用户 ID；
- 允许角色；
- 目标路径；
- 签发与过期时间。

Redis 不保存原始 bearer 值。消费使用 Lua 原子执行 `GET + DEL`，因此多个 Chat
实例同时收到相同票据时，最多一个实例得到身份快照。路径不匹配在访问 Redis 前拒绝；
命名空间、用户、角色、目标路径或过期时间不合法时，即使值已经取出也拒绝握手。

该机制只保证握手票据单次消费，不把 WebSocket 消息投递解释为 exactly-once。
消息正文、离线回放、未读和回执事实继续由 MySQL 裁决。

## 4. 安全边界

`/ws/chat` 在 HTTP Security 层允许进入握手流程，是为了同时支持 Header JWT 和
浏览器票据，不代表匿名连接被接受。`ChatWebSocketHandshakeInterceptor` 是该端点
的强制认证边界：

- 有有效 `Authorization` Header 时继续兼容原有客户端；
- 无 Header 时必须且只能提供一个 `ticket` 查询参数；
- 缺失、重复、格式错误、过期、已消费或路径不匹配均返回 401；
- Redis 无法读取票据时返回 503，不降级为匿名或伪造成功；
- 签发时 Redis 不可用同样返回 503 和 `CHAT_REALTIME_UNAVAILABLE`；
- 无有效用户或允许角色时换票返回
  `WEBSOCKET_TICKET_ACCESS_DENIED`。

生产入口仍必须使用 TLS，并对 Gateway、反向代理和可观测系统中的查询参数做脱敏。
短 TTL、单次消费和应用日志不记录原票据只能降低暴露面，不能替代传输加密和日志
治理。

## 5. 自动化与静态门禁

针对性命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn -pl services/chat-service -am clean test
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
mvn -pl services/chat-service `
  com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs `
  '-Dspotbugs.effort=Max' `
  '-Dspotbugs.threshold=Low' `
  -DskipTests
```

结果：

- `platform-common`：14 tests；
- `chat-service`：26 tests；
- 0 失败、0 错误、0 跳过；
- 干净测试编译无 unchecked 警告；
- 全 Reactor PMD 0 违规；
- Chat SpotBugs 低阈值专项 35 条诊断：Priority 1 为 0、Priority 2 为 26、
  Priority 3 为 9；Priority 2 均为 Spring 单例构造注入依赖的
  `EI_EXPOSE_REP2`。

新增覆盖：

- 票据为 32 字节不透明 Base64URL，Redis Key 只使用摘要；
- 16 路并发消费最多一个成功；
- 错误路径不消费票据；
- 过期票据拒绝；
- Redis 签发和消费故障均失败关闭；
- 浏览器无 Header 握手；
- 重复查询参数拒绝；
- 原有 JWT Header 客户端兼容；
- 换票响应禁止缓存。

最终全量门禁：

```powershell
mvn clean verify
```

结果为 76 份 Surefire 报告、274 tests，12 个 Reactor 模块全部成功，0 失败、
0 错误、0 跳过。

## 6. 真实 Gateway、双 Chat 实例与 Redis 故障证据

命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
./tools/verify-m8-chat-browser-ticket.ps1 -SkipPackage
```

权威证据：

```text
backend/.run/m8-chat-browser-ticket-m8wse75d2b880a0a/verification.json
```

关键结果：

```json
{
  "issuance": {
    "opaqueTicketLength": 43,
    "rawTicketStoredInRedis": false,
    "cacheControlNoStore": true,
    "pragmaNoCache": true,
    "redisTtlSeconds": 10
  },
  "singleUse": {
    "replayRejected": true,
    "redisKeysAfterConsumption": 0
  },
  "crossInstance": {
    "operatorRolePreserved": true
  },
  "expiry": {
    "expiredTicketRejected": true,
    "redisKeysAfterExpiry": 0
  },
  "redisFailure": {
    "issuanceStatus": 503,
    "issuanceCode": "CHAT_REALTIME_UNAVAILABLE",
    "handshakeRejected": true,
    "healthyNodeConsumedSameTicket": true
  },
  "compatibility": {
    "authorizationHeaderAccepted": true
  },
  "privacy": {
    "rawTicketInApplicationLogs": 0
  },
  "cleanup": {
    "mysqlRows": 0,
    "redisKeys": 0,
    "portListeners": 0,
    "managedJvms": 0
  }
}
```

真实运行先通过既有七项网络预检，没有修改代理、路由、网卡或 Docker 配置。脚本
启动两个健康 Chat 实例、一个 Redis 故障 Chat 实例和 Gateway，验证 Gateway 路由、
跨实例签发/消费、重放、过期、Header 兼容和失败关闭。结束后 Chat 七表、运行前缀
Redis Key、18000/18108/18118/18128 端口和受管 JVM 均无残留。

## 7. M8.4 结论与后续边界

M8.4 已关闭浏览器 WebSocket 握手前的认证缺口：

- 长期 JWT 只用于受保护 REST 换票；
- 短期票据不透明、禁止缓存、摘要索引、单次消费；
- 多实例共享 Redis 原子裁决；
- Redis 故障失败关闭；
- 原有 Header JWT 客户端保持兼容；
- 真实 Gateway、双实例、故障与零残留证据通过。

以下是本批交付时尚未完成、现已由后续批次关闭的历史清单：

- Chat 专属持久化 `consumer_failure` 台账；
- 独立恶意文件扫描与隔离区；
- Chat 顾客端与客服工作台；
- Notification、物流 GEO、评价、搜索和运营统计。

2026-07-25 当前状态以
[M8 全面审查、回归与毕业收口](68-m8-full-audit-and-graduation-20260725.md)
为准：M8.1–M8.12 已完成并关闭。
