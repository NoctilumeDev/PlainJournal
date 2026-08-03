# M8 第五批：Chat 持久化消费失败治理

> 状态：已完成；2026-07-25 经 M8 整体审查修正恢复所有权  
> 初次完成日期：2026-07-23  
> 当前证据日期：2026-07-25  
> 阶段边界：只完成 Chat 两个 RocketMQ 消费器的持久化失败台账、状态转换、只读观测与真实恢复验证；不宣称前端会话工作区、恶意文件扫描、Notification、GEO、评价、搜索、统计或整个 M8 完成

## 1. 本批目标

M8.2 已完成 `ChatMessageStored` 和 `ChatDeliveryRequested` 的真实 RocketMQ 投递，
但历史实现只在无效契约时记录错误日志并 ACK。日志不能承担待处理事实，也无法回答：

- 哪条消息正在重试；
- 哪条毒消息已经耗尽或无法消费；
- 当前失败属于哪个 consumer group；
- Redis 或数据库恢复后，原失败是否已经收敛；
- 运维入口是否在不暴露私聊载荷的前提下提供可核对事实。

M8.5 为 Chat 增加本服务所有的 `consumer_failure` 台账，并把两个消费器统一为：

```text
临时失败
  -> RETRYING
  -> 持久化 next_attempt_at
  -> 台账提交成功后 ACK 原消息
  -> MySQL 租约作业有限重试
  -> 后续成功后 RECOVERED
  -> 持续失败后 NEEDS_ATTENTION

无效契约或投递耗尽
  -> NEEDS_ATTENTION
  -> 台账成功落库后才 ACK
```

MySQL 是失败治理事实所有者。RocketMQ 负责把原始事实至少送达消费边界，Redis
仍只负责在线节点路由；临时失败事实一旦由 MySQL 接管，恢复不再依赖 Broker POP
revive。失败台账本身无法落库时不 ACK，任何中间件异常都不能把消息伪造为已成功
处理。

## 2. 数据模型与状态

Flyway `V6__create_consumer_failure_table.sql` 新增基础台账：

- `message_id + consumer_group` 联合主键；
- `raw_payload`：仅供领域内诊断和后续受控恢复，不通过 Actuator 返回；
- `attempts`：Broker 当前投递次数；
- `status`：`RETRYING / NEEDS_ATTENTION / RECOVERED`；
- `last_error`；
- `first_failed_at / last_failed_at / recovered_at`。

M8 整体审查新增 `V8__add_consumer_failure_retry_lease.sql`：

- `next_attempt_at`：下一次重试时间；
- `claimed_at / claim_owner / claim_until`：多实例安全租约；
- `status + next_attempt_at + claim_until` 重试索引。

状态含义：

| 状态 | 含义 | 所有权与动作 |
| --- | --- | --- |
| `RETRYING` | 处理依赖暂时不可用，仍在有限预算内 | MySQL 保存载荷、下次时间和租约；初次持久化成功后 ACK 原消息 |
| `NEEDS_ATTENTION` | 契约无效，或投递次数达到阈值 | 台账落库后 ACK |
| `RECOVERED` | MySQL 租约作业后续处理成功，或目标已经离线并可由历史回放恢复 | 清空调度和租约字段 |

默认最大投递次数为 16，可通过
`CHAT_CONSUMER_FAILURE_MAX_DELIVERY_ATTEMPTS` 调整。测试和真实故障脚本可以降低阈值，
正式默认值不变。

## 3. 两个消费器的 ACK 边界

### 3.1 `ChatStoredEventConsumer`

- `payloadVersion != 1`、错误事件类型或缺失必填字段：写入
  `NEEDS_ATTENTION`，成功后 ACK；
- Redis presence 查询或定向事件发布临时失败：写入 `RETRYING` 和
  `next_attempt_at`，事务提交成功后 ACK；
- 投递次数达到阈值：更新为 `NEEDS_ATTENTION` 后 ACK；
- 后续由 MySQL 租约作业重新计算在线节点并发布定向事件，成功后
  `RECOVERED`；
- 无在线节点是正常业务结果，源消息仍可成功 ACK，不制造失败台账。

### 3.2 `ChatDeliveryEventConsumer`

- 无效契约或目标节点 Tag/载荷不一致：写入 `NEEDS_ATTENTION` 后 ACK；
- MySQL、WebSocket 写入或其他临时处理失败：写入 `RETRYING`，持久化成功后
  ACK；
- 投递耗尽：写入 `NEEDS_ATTENTION` 后 ACK；
- 后续由 MySQL 租约作业成功处理：更新为 `RECOVERED`；
- 目标连接在写入前已离线属于可恢复业务结果，MySQL `OFFLINE` 回执仍是权威事实，
  因此初次消费或租约重试都收敛为成功，不制造人工关注。

`ChatConsumerFailureRetryJob` 只扫描当前 Dispatcher group 和当前节点 Delivery
group 的到期记录。每条记录先以条件更新抢占租约，再在事务外执行领域动作；只有
当前 owner 且租约未过期时才能写 `RECOVERED` 或重新安排。租约丢失后不会由旧
owner 覆盖新 owner 的结果。两个消费器继续遵守“失败事实先落库，再决定是否
ACK”；台账无法落库时异常向外传播，不能为了清队列而丢弃消息。

2026-07-25 最终审查又补充了晚到重复消息的状态守卫：

- 只有未被有效租约持有的 `RETRYING` 记录允许由 Broker 失败路径更新；
- `RECOVERED` 和 `NEEDS_ATTENTION` 都不能被晚到的低投递次数重新降级；
- `attempts` 单调不减，重复投递不能覆盖 MySQL 作业已经消耗的重试预算；
- `next_attempt_at` 保留更早的到期时间，重复投递不能无限推迟恢复；
- 已恢复记录或有效租约记录未发生状态变化时，不重复增加失败观测计数。

## 4. 只读观测与安全

Chat 复用平台统一 `ConsumerFailureObservability`：

```http
GET /actuator/consumerfailures
Authorization: Bearer <admin-jwt>
```

边界：

- 只有 `ADMIN` 可读；
- 返回 `RETRYING / NEEDS_ATTENTION / RECOVERED` 数量、最老活动失败年龄和有界活动列表；
- 活动列表不包含 `raw_payload`；
- metrics 采集继续使用专用 `X-Metrics-Token`，不能用普通管理员令牌替代；
- 该端点只读，不提供跨库改状态或“直接标记成功”能力。

Micrometer 指标包括：

- `ecommerce.consumer.failure.active`；
- `ecommerce.consumer.failure.oldest.age`；
- `ecommerce.consumer.failure.transitions`。

Prometheus 暴露时转换为标准下划线名称，并带
`service="chat-service"`、状态或转换结果标签。

## 5. 自动化与静态门禁

命令：

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
- `chat-service`：53 tests；
- Chat 冷测试合计 67 tests，0 失败、0 错误、0 跳过；
- 2026-07-25 M8 收口快照为全后端 14 个 Reactor 模块、97 份 Surefire 报告、
  399 tests，0 失败、0 错误、0 跳过；
- 全后端 PMD Maven Plugin 3.28.0 / PMD 7.17.0：0 违规；
- Chat SpotBugs 低阈值专项 43 条诊断：Priority 1 为 0、Priority 2 为 33、
  Priority 3 为 10。

2026-07-28 进入 M9 前复审后的当前基线为 100 份 Surefire 报告、435 tests，
前端 106 个 Vitest 与 7 个 Playwright E2E；本节其余数字保留为 M8.5 历史快照。

新增覆盖：

- 毒消息必须在持久化失败事实后 ACK；
- 临时失败持久化重试所有权后 ACK；
- 投递耗尽后 `NEEDS_ATTENTION` 并 ACK；
- Dispatcher/Delivery 租约重试、租约竞争和过期接管；
- 临时失败重新安排、非法载荷直接终态；
- 后续成功将同一失败转为 `RECOVERED` 并清空租约；
- 晚到重复失败不能回退 `RECOVERED/NEEDS_ATTENTION` 或清除有效租约；
- Broker 投递次数不能降低 MySQL 重试次数，也不能推迟更早的重试时间；
- 目标离线不误记为消费失败；
- Actuator 数量、活动列表、权限和原始载荷隐藏；
- Prometheus 活动数量、最老年龄和转换指标。

## 6. 真实 MySQL、Redis 与 RocketMQ 故障证据

首次使用：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
./tools/verify-m8-chat-consumer-failures.ps1 -SkipPackage
```

资源已经由同一会话完整 bootstrap 后，可快速复验：

```powershell
./tools/verify-m8-chat-consumer-failures.ps1 `
  -SkipPackage `
  -SkipResourceBootstrap
```

权威证据：

```text
backend/.run/m8-chat-consumer-failures-m8cf20260725024042/verification.json
```

关键结果：

```json
{
  "poisonMessage": {
    "outboxStatus": "PUBLISHED",
    "durableStatus": "NEEDS_ATTENTION",
    "acknowledgedAfterRecording": true,
    "rawPayloadExposedByActuator": false
  },
  "transientRedisFailure": {
    "firstStatus": "RETRYING",
    "firstRecordedAttempts": 1,
    "finalAttempts": 2,
    "finalStatus": "RECOVERED",
    "redisRestored": true,
    "originalAcknowledgedAfterDurableRetry": true,
    "recoveryOwner": "mysql-lease-retry",
    "brokerRedeliveryRequired": false
  },
  "observability": {
    "runDelta": {
      "retrying": 0,
      "needsAttention": 2,
      "recovered": 1
    },
    "prometheusMetricsPresent": true
  },
  "cleanup": {
    "mysqlRows": 0,
    "residualRocketMqConsumerGroups": [],
    "portListeners": 0,
    "managedJvms": 0
  }
}
```

真实脚本只启动一个 Chat JVM：

1. 先通过 API 创建真实消息并等待正常 Outbox 发布；
2. 再插入引用该真实 `messageId`、但 `payloadVersion = 99` 的毒消息；
3. 验证发布器允许其进入 Broker，消费者持久化 `NEEDS_ATTENTION` 后 ACK；
4. 停止 Redis 后发送另一条有效消息；
5. 验证其进入 `RETRYING`、存在 `next_attempt_at`，且原消息只在持久化成功后
   ACK；
6. 恢复 Redis，等待 MySQL 租约作业将同一失败转为 `RECOVERED`；
7. 验证 Actuator、Prometheus 和最终零残留。

脚本按当前事件标记和业务 `messageId` 精确查询，避免 RocketMQ 保留期内的历史
验证消息污染核心断言。JVM 直接使用
`JAVA_HOME\bin\java.exe`，并带专用系统属性标记；清理只停止同时匹配 PlainJournal
路径、Chat Jar 和 verifier 标记的进程。

当前恢复门禁为 90 秒，每 15 秒输出进度。脚本不修改 Broker、网卡、路由、代理或
Docker 网络配置。

### 6.1 失败基线与架构修正

2026-07-25 两次真实 Redis 故障实验分别保存在：

```text
backend/.run/m8-chat-consumer-failures-m8cf20260725012254
backend/.run/m8-chat-consumer-failures-m8cf20260725013638
```

两次在 Redis 恢复后等待 360 秒仍未转为 `RECOVERED`。第二次已经在 JVM 启动前
预创建运行级消费组，并二次确认：

```text
consumeFromMinEnable=false
consumeBroadcastEnable=false
```

因此“消费组自动创建竞争”不是根因。Broker 权威统计为：

```text
GROUP_GET_NUMS = 7
GROUP_CK_NUMS  = 8
GROUP_ACK_NUMS = 7
```

额外一次不可见时间调整到达 Broker，旧 receipt 也被确认，但 6 分钟内没有 retry
topic、revive 或第二次 GET。继续增加等待时间不能形成可靠机制。旧成功证据
`m8cf20260723221011` 依赖长期存在的固定消费组和已有 retry topic，只能保留为历史
证据，不能代表新消费组可稳定复现。

最终修正为 MySQL 持久化重试事实和多实例安全租约。原消息只有在失败事实提交后才
ACK；进程在 ACK 后退出、Redis 长时间不可用、多个 Chat 实例竞争或旧 owner
恢复，都由 `next_attempt_at + claim_owner + claim_until` 继续裁决。

### 6.2 运行级消费组与保留消息边界

整体审查确认，历史脚本使用固定验证消费组且未删除 Broker 侧消费组元数据。当前
脚本改为运行级 Dispatcher 与节点投递消费组，并把以下条件纳入成功门禁：

- 停止 Chat JVM 后再删除本次两个消费组；
- 删除最多重试三次，并在稳定等待后用 `getConsumerConfig` 二次反查；
- `finally` 重复执行幂等清理，失败时保留明确告警；
- MySQL 失败台账仍按本次消费组和业务标记精确清理；
- 新证据必须包含 `cleanup.residualRocketMqConsumerGroups = []`。

运行级消费组可能读取 Topic 保留期内的旧事件，因此观测总量只作为辅助信息；毒
消息终态与 Redis 恢复断言继续绑定本次唯一 payload marker 和 `messageId`，不能
用全局计数替代。

10:06 的成功运行读取到 09:37:31 的历史毒消息；最终 10:41 的成功运行又读取到
10:06:48 的历史毒消息和本次毒消息，所以两次 Actuator 的 `NEEDS_ATTENTION`
增量都可能大于本次注入数。脚本通过唯一 marker 精确证明当前毒消息只有一条对应
记录；这不是业务重复写入，也再次说明不能用全局计数替代运行身份。

## 7. M8.5 结论与后续边界

M8.5 已关闭 Chat 消费失败只存在于日志中的治理缺口，并在 M8 整体审查中关闭了
对 Broker revive 的隐含依赖：

- 两个消费器具有一致的“持久化后 ACK、MySQL 租约有限重试、终态人工关注”语义；
- MySQL 保存可查询、可审计的失败与恢复事实；
- 毒消息不会永久占用队列；
- 临时故障不会被伪造成成功；
- 新消费组是否存在 retry topic 不再影响恢复正确性；
- 过期租约可由其他实例接管，旧 owner 无法覆盖新 owner；
- 原始私聊事件载荷不通过运维端点暴露；
- 真实 Redis 停机、MySQL 租约恢复和零残留证据通过。

M8.1–M8.12 及阶段整体审查现已完成。M8 毕业结论、全量门禁、供应链修复和 M9
准入见 [M8 全面审查、回归与毕业收口](68-m8-full-audit-and-graduation-20260725.md)。
