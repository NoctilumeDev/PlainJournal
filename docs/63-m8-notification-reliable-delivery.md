# M8 第八批：可靠通知投递与审计恢复

> 状态：已完成  
> 完成日期：2026-07-24  
> 阶段边界：完成站内信与邮件可靠投递首版；不包含前端通知中心、短信/推送、真实外部邮件供应商、退信/投诉处理、营销群发或整个 M8 完成

## 1. 本批目标

本批新增 `notification-service`（18109），用交易与履约已经发生的事实生成通知，
但不参与订单、支付、退款或履约状态裁决。

首批消费：

```text
PaymentSucceeded
RefundSucceeded
ShipmentDispatched
ShipmentSigned
```

每个事件在一个 Notification MySQL 本地事务中写入：

```text
consumed_event
  + notification_task
  + in_app_notification
  + notification_delivery（用户启用邮件时）
```

MQ 重投不能重复生成通知。SMTP 故障不能回滚站内信，也不能让上游交易失败；邮件保持
可解释的重试或人工关注状态。

## 2. 服务与数据边界

新增模块：

```text
backend/services/notification-service
```

基础设施：

```text
port: 18109
schema: ecom_notification
gateway route: /api/v1/notifications/**
nacos data id: notification-service.yml
```

核心表：

- `notification_recipient`：用户邮件地址与启用偏好；
- `notification_task`：来源事件、模板、业务引用和渲染后内容；
- `in_app_notification`：用户站内信及 `UNREAD / READ`；
- `notification_delivery`：邮件状态、租约、尝试次数、错误和稳定消息 ID；
- `notification_delivery_retry_audit`：管理员恢复命令的追加式审计；
- `consumed_event`：来源事件幂等；
- `consumer_failure`：毒消息和临时消费失败事实。

本服务使用 Spring JDBC 保存这些简单、明确的持久化模型，没有复制无业务价值的
Entity/Mapper 样板。MySQL 是最终事实；RocketMQ、SMTP 和 Actuator 均不能直接修改
通知状态。

## 3. API 与权限

顾客接口：

```http
GET  /api/v1/notifications
GET  /api/v1/notifications/unread-count
POST /api/v1/notifications/{notificationId}/read
PUT  /api/v1/notifications/email-preference
```

- 列表使用 keyset cursor；
- 只能读取和修改自己的站内信；
- 重复已读命令幂等；
- 启用邮件时必须提供合法邮箱。

恢复接口：

```http
POST /api/v1/notifications/admin/email-deliveries/{deliveryId}/retry
```

- 只允许 `ADMIN/OPERATOR`；
- `commandId` 是稳定幂等键；
- `reason` 必填并进入审计；
- 只允许 `NEEDS_ATTENTION -> RETRY`；
- 相同命令只产生一条审计；
- 相同 `commandId + deliveryId + operatorId + reason` 重放返回原结果；
- 相同 `commandId` 携带不同投递、操作者或原因时返回 `409 IDEMPOTENCY_CONFLICT`；
- 顾客调用返回 `403`；
- 命令不能直接写 `SENT`。

`/actuator/prometheus` 使用独立采集身份，`/actuator/consumerfailures` 只允许
`ADMIN` 读取有界摘要，不返回 `raw_payload`。

## 4. 邮件租约、有限重试与不确定性

状态机：

```text
PENDING -> SENDING -> SENT
              |
              +-> RETRY -> SENDING
              |
              +-> NEEDS_ATTENTION

NEEDS_ATTENTION -> 管理员幂等审计命令 -> RETRY
```

执行边界：

1. 调度器从 MySQL 查询到期任务；
2. 短事务内通过状态、`claim_owner` 和 `claim_until` 抢占；
3. 提交事务后调用 SMTP；
4. 明确成功才写 `SENT`；
5. 失败未达到上限写 `RETRY`，达到上限写 `NEEDS_ATTENTION`。

每个邮件任务在创建时保存稳定 `provider_message_id`，重试复用同一 `Message-ID`。
这有助于邮件供应商或收件系统去重，但不能证明 SMTP exactly-once：

- SMTP 接受邮件且正常响应：本地写 `SENT`；
- SMTP 明确连接失败：本地有限重试；
- SMTP 已接受邮件但响应丢失：本地无法确认结果，重试可能产生重复邮件。

因此本批承诺的是可持久化、可重试、可审计和不伪造成功，不承诺外部邮箱只收到一次。

## 5. 消费幂等与毒消息治理

合法事件必须满足：

- `payloadVersion = 1`；
- `eventType` 位于首批白名单；
- `producer / aggregateType` 与事件类型的生产者契约一致；
- `aggregateId` 与 `paymentNo / refundNo / fulfillmentNo` 一致；
- `aggregateVersion >= 0`；
- `userId` 为正数；
- 模板所需订单号、退款号、承运商或运单号存在。

处理规则：

- `event_id + consumer_group` 唯一，幂等记录和通知副作用同事务；
- `notification_task.source_event_id` 再提供一层唯一事实保护；
- 重复 MQ 消息确认但不重复生成任务、站内信或邮件；
- 无效版本或无效契约先写 `consumer_failure.NEEDS_ATTENTION`，再 ACK；
- 临时数据库失败写失败事实但不 ACK，保留 Broker 重投能力；
- 后续成功可把失败事实推进为 `RECOVERED`。

## 6. 自动化与静态门禁

定向命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn -pl services/notification-service -am test
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
mvn -pl services/notification-service `
  com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs `
  '-Dspotbugs.effort=Max' `
  '-Dspotbugs.threshold=Low' `
  '-Dspotbugs.xmlOutput=true' `
  -DskipTests
```

结果：

- `platform-common`：14 tests；
- `notification-service`：7 tests；
- 定向合计 21 tests，0 失败、0 错误、0 跳过；
- 全 Reactor PMD 3.28.0 / PMD 7.17.0：0 违规；
- Notification SpotBugs 低阈值专项：8 条诊断，其中 Priority 1 为 0、
  Priority 2 为 6、Priority 3 为 2。

SpotBugs 的 6 条 Priority 2 包括 5 条 Spring 单例构造注入引用告警和 1 条 SQL
文本块格式化告警；2 条 Priority 3 是 RocketMQ 客户端边界的宽异常声明。没有发现
高优先级内存、并发、安全或资源泄漏问题。

本批完成后重新执行：

```powershell
mvn clean verify
```

结果为 13 个 Reactor 模块全部成功，84 份 Surefire 报告、297 tests，0 失败、
0 错误、0 跳过。

新增自动化覆盖：

- 8 路并发重复事件收敛为一个通知事实；
- 站内信用户隔离、未读数量和幂等已读；
- 邮件两次失败进入 `NEEDS_ATTENTION`；
- 顾客恢复 403；
- 管理员恢复命令幂等并只写一条审计；
- SMTP 使用稳定 `Message-ID`；
- 毒消息持久化后 ACK；
- 临时消费失败不 ACK，恢复后 ACK。

## 7. 真实 MySQL、RocketMQ、Gateway 与 SMTP 证据

验证命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\tools\verify-m8-notification-delivery.ps1 -SkipBuild
```

脚本先执行 `docs/07-local-development-network.md` 的完整门禁，然后：

1. 幂等初始化 Notification schema、Nacos 配置和运行隔离 Topic；
2. 临时启动 Notification、Payment 和 Gateway；
3. 经 Gateway 保存顾客邮件偏好；
4. 通过 Payment MySQL Outbox 发布真实 `PaymentSucceeded`；
5. SMTP 不监听时等待两次失败并进入 `NEEDS_ATTENTION`；
6. 发布同一 `eventId` 的第二条 MQ 事实，等待 RocketMQ 消费位点归零并核对数据库
   仍只有一个通知事实；
7. 验证站内信仍可读且未读数为 1；
8. 验证顾客恢复 403；
9. 启动最小本地 SMTP 捕获器，管理员相同命令调用两次；
10. 等待邮件进入 `SENT`，核对一条审计和捕获内容中的稳定 `Message-ID`；
11. 发布无效 `payloadVersion`，核对 `consumer_failure.NEEDS_ATTENTION` 和
    Actuator 原始载荷隐藏；
12. 清理运行数据、隔离 Topic、端口和进程。

权威证据：

```text
backend/.run/m8-notification-20260724-195313/verification.json
```

关键结果：

```json
{
  "smtpUnavailable": {
    "inAppUnread": 1,
    "deliveryStatus": "NEEDS_ATTENTION",
    "duplicateSourceEventsConverged": true,
    "attempts": 2
  },
  "recovery": {
    "customerRetryHttpStatus": 403,
    "adminRetryHttpStatus": 200,
    "repeatedAdminRetryHttpStatus": 200,
    "finalDeliveryStatus": "SENT",
    "auditRows": 1,
    "capturedMessages": 1
  },
  "poisonEvent": {
    "status": "NEEDS_ATTENTION",
    "actuatorHttpStatus": 200,
    "rawPayloadExposed": false
  },
  "cleanup": {
    "notificationRowsRemaining": 0,
    "managedPortsListening": 0,
    "temporaryJvmProcesses": 0,
    "isolatedTopicsRemoved": true
  }
}
```

最终复核确认：

- `18000 / 18105 / 18109 / 12525` 无监听；
- 没有临时 Gateway、Payment、Notification JVM；
- 两个运行隔离 Topic 已删除；
- Notification 与 Payment 验证数据为 0；
- SMTP 捕获进程已退出；
- 驱动脚本标准错误为空。

M8 整体审查随后发现，首版脚本只相信 `deleteTopic` 返回且没有删除运行隔离消费组。
2026-07-24 的旧验证消费组因此仍留在 Broker；该精确消费组已经删除并通过
`getConsumerConfig` 反查不存在。脚本现改为停止消费者后有限重试删除消费组，使用
`getConsumerConfig` 和 `topicList` 反查消费组与 Topic，并额外写出 `cleanup.json`；
任何 Broker 元数据残留都会阻断验证成功。

## 8. 脚本与本机边界

本机 `PATH` 中的 `java.exe` 是 Oracle shim，会返回包装进程 PID，而实际 JVM 使用
另一个 PID。验证脚本因此优先使用 `JAVA_HOME\bin\java.exe`，并在清理阶段同时核对：

- 启动时记录的 PID；
- 端口实际监听 PID；
- 进程名；
- 命令行中的预期 PlainJournal JAR 或 SMTP 捕获脚本。

命令行不匹配时拒绝终止，避免误杀用户进程；匹配后等待端口关闭并再次断言零残留。
该修复没有修改机器 PATH、路由、代理、网卡跃点、Docker 数据或镜像源。

## 9. M8.8 结论与后续边界

M8.8 已关闭“交易事实已经发生，但通知只能同步发送或失败后不可解释”的缺口：

- 通知事实由 Notification MySQL 独立拥有；
- 站内信不受 SMTP 故障影响；
- MQ 重投不重复生成通知；
- 邮件调度支持多实例租约、有限重试和人工关注；
- 管理恢复受角色、幂等键、原因和追加审计约束；
- 中间件异常不伪造成功；
- 毒消息可观测且不通过 Actuator 泄露原始载荷。

前端通知中心、真实邮件供应商、退信/投诉、外部送达状态和群发能力尚未实施。本批
交付时整个 M8 尚未完成；2026-07-25 当前状态以
[M8 全面审查、回归与毕业收口](68-m8-full-audit-and-graduation-20260725.md)
为准：物流 GEO、商品评价、搜索和运营统计已按独立闭环完成，M8 已关闭。
