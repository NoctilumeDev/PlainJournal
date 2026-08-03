# M8 第十批：商品评价、并发幂等与审核治理

## 1. 批次结论

M8.10 已完成商品评价的核心业务闭环：

- Trade 在订单签收并迁移为 `COMPLETED` 时，通过同事务 Outbox 发布
  `OrderCompleted`；
- 事件携带成交时保存的不可变订单行快照，Catalog 不回查 Trade 数据库，也不在
  顾客提交评价时同步调用 Trade；
- Catalog 幂等生成按订单行划分、按顾客所有者隔离的评价资格；
- 顾客可以提交一次事实评价、点赞/取消点赞和举报其他顾客的公开评价；
- `ADMIN/OPERATOR` 可以幂等回复，并以带原因、追加审计的命令处理举报；
- 举报成立时评价进入 `HIDDEN`，公开评分汇总在同一事务扣减，不继续影响商品评分；
- 顾客端订单详情、商品详情和管理端评价治理工作区已经接入。

本批没有实现评价图片/视频、追评、商户回复、多维度评分、敏感词模型或推荐排序。
在本批交付时，搜索和运营统计仍是后续两个独立闭环。

## 2. 事件与所有权边界

```text
Fulfillment ShipmentSigned
          |
          v
Trade COMPLETED + OrderCompleted Outbox
          |
          | RocketMQ / payloadVersion=1
          v
Catalog consumed_event + review_eligibility
          |
          v
评价、汇总、点赞、回复、举报与审核
```

`OrderCompleted` 的 `payload` 包含：

- `orderNo`、`userId`；
- 每个订单行的 `lineNo`、`productId`、`skuId`；
- 成交时固化的 `productTitle`、`skuCode`、`skuName`、`specJson`、
  `imageObjectKey` 和 `quantity`。

完成时间取事件信封的 `occurredAt`。Catalog 校验 `producer=trade-service`、
`aggregateType=TradeOrder`、`aggregateId=payload.orderNo`、非负聚合版本、事件类型、
载荷版本、用户、订单号和全部订单行字段后，才在自己的 schema 中创建评价资格。

正确性边界：

1. Trade 只拥有订单完成事实和不可变订单行快照，不拥有评价状态。
2. Catalog 只根据版本化事件维护本地评价资格，不直接查询 Trade 表。
3. 同一 `eventId + consumerGroup` 只消费一次；同一 `orderNo + lineNo` 只生成一份
   资格。
4. 资格查询和评价提交都使用 JWT subject，跨账户资格表现为 404，不泄露其存在。
5. RocketMQ 不可用时 Trade Outbox 保持 `PENDING`；只有 Broker 确认后才标记
   `PUBLISHED`。

## 3. 数据与状态

Catalog Flyway `V4__create_product_review_tables.sql` 新增：

| 表 | 所有者事实 |
| --- | --- |
| `review_eligibility` | 订单行快照、顾客、完成时间和 `ELIGIBLE/REVIEWED` 状态 |
| `product_review` | 一次评价、请求哈希、公开状态和点赞计数 |
| `product_review_summary` | 公开评价数、评分总和和 1–5 星计数 |
| `review_reply` | 每条评价最多一条平台回复及幂等命令 |
| `review_like` | `review_id + user_id` 唯一点赞事实 |
| `review_report` | 举报原因和 `OPEN/RESOLVED` 生命周期 |
| `review_moderation_audit` | 审核命令、原因、前后状态和操作者的追加审计 |
| `consumed_event` | `OrderCompleted` 消费幂等事实 |
| `consumer_failure` | 消息失败尝试、状态、错误摘要和恢复时间 |

核心状态：

```text
review_eligibility: ELIGIBLE -> REVIEWED
product_review:     PUBLISHED -> HIDDEN
review_report:      OPEN -> RESOLVED(UPHELD | REJECTED)
consumer_failure:   RETRYING -> RECOVERED
                         \----> NEEDS_ATTENTION
```

`UPHELD` 只在评价仍为 `PUBLISHED` 时隐藏评价并扣减一次汇总；`REJECTED` 保持公开状态。
重复审核使用稳定 `commandId + requestHash` 返回原结果，异载荷重放返回幂等冲突。

## 4. 接口与权限

| Method | Path | 权限 | 语义 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/catalog/products/{productId}/review-summary` | 公开 | 只统计公开评价 |
| `GET` | `/api/v1/catalog/products/{productId}/reviews` | 公开/可选登录 | 公开评价分页；登录后返回当前用户点赞状态 |
| `GET` | `/api/v1/catalog/review-eligibilities` | `CUSTOMER` | 当前顾客的订单行资格，可按订单号过滤 |
| `POST` | `/api/v1/catalog/reviews` | `CUSTOMER` | 使用 `Idempotency-Key` 提交评价 |
| `POST/DELETE` | `/api/v1/catalog/reviews/{reviewId}/likes` | `CUSTOMER` | 幂等点赞/取消点赞 |
| `POST` | `/api/v1/catalog/reviews/{reviewId}/reports` | `CUSTOMER` | 举报其他顾客的公开评价 |
| `GET` | `/api/v1/catalog/admin/reviews/reports` | `ADMIN/OPERATOR` | 按状态分页读取举报 |
| `POST` | `/api/v1/catalog/admin/reviews/{reviewId}/reply` | `ADMIN/OPERATOR` | 使用幂等键保存平台回复 |
| `POST` | `/api/v1/catalog/admin/reviews/reports/{reportId}/resolve` | `ADMIN/OPERATOR` | 带命令 ID 和原因审核举报 |

评价、资格和浏览器业务 ID 使用局部字符串序列化，避免 JavaScript 对 64 位整数丢失
精度；服务内部和数据库仍使用 `BIGINT`。

## 5. MySQL 并发幂等修复

首次真实 MySQL 8.4 并发验证中，8 路完全相同的评价请求出现 5 路 HTTP 200、3 路
HTTP 409 `REVIEW_ALREADY_SUBMITTED`。数据库最终只有一条评价，但相同命令没有
稳定返回同一结果，仍然违反 API 幂等契约。

根因是 MySQL 默认 `REPEATABLE READ`：

1. 请求先读取 `user_id + idempotency_key`，建立事务一致性读快照；
2. 多个请求随后竞争同一资格行的 `FOR UPDATE` 锁；
3. 等待锁释放后再次执行普通一致性读，仍可能看到旧快照；
4. 请求因资格已变为 `REVIEWED` 而误报 409。

最终把 `createReview` 明确设置为：

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
```

资格行锁、`uk_product_review_eligibility` 和
`uk_product_review_user_idempotency` 继续承担最终约束。锁后幂等复查在
`READ_COMMITTED` 下能读取前序事务已提交的评价，因此 8 路相同请求全部返回同一个
评价 ID；同键异载荷仍返回 `IDEMPOTENCY_CONFLICT`。集成测试额外固定了事务隔离级别
契约，防止以后无意恢复默认隔离级别。

## 6. 消费失败治理

Catalog 的 `OrderCompletedConsumer` 区分两类失败：

- JSON、事件类型、`payloadVersion` 或必填字段无效：先写
  `consumer_failure.NEEDS_ATTENTION`，再 ACK，避免毒消息无限阻塞消费组；
- 数据库等暂态失败：写 `RETRYING` 且不 ACK，交给 Broker 重投；超过有限投递次数后
  进入 `NEEDS_ATTENTION` 并 ACK。

同一消息后续处理成功时，已有失败记录转为 `RECOVERED`。台账保留原始载荷用于受控
诊断，但 Actuator/Prometheus 不暴露原始业务载荷；指标只记录低基数状态转换。

真实验证脚本最初还暴露了一个验证工具问题：把含 `specJson` 的嵌套 JSON 直接拼接到
MySQL SQL 字符串会丢失反斜杠，Catalog 正确把损坏消息识别为毒消息。脚本现改为
Base64 无损写入，并在发布前复核数据库中的 `specJson`。

## 7. 前端闭环

顾客端：

- 完成订单详情按订单号读取本人的评价资格；
- 同一次提交保持稳定幂等键，成功后显示 `已评价`；
- 商品详情展示评分汇总、公开评价、匿名作者、规格快照、平台回复和点赞状态；
- 顾客可以点赞、取消点赞和举报其他顾客的评价。

管理端：

- `/reviews` 工作区分页读取 `OPEN/RESOLVED` 举报；
- 平台回复和审核各自使用稳定命令 ID；
- 审核必须记录明确原因；
- 举报成立并隐藏评价后，商品详情公开列表与评分汇总同步反映所有者事实。

Playwright E2E 覆盖完成订单提交评价、商品详情点赞/举报、管理员回复、审核成立、
公开评分移除和 axe 可访问性检查。

## 8. 自动化与静态门禁

最终后端：

```text
mvn clean verify
86 份 Surefire 报告
306 tests
0 failures / 0 errors / 0 skipped
```

模块重点：

```text
catalog-service   25 tests
trade-service    104 tests
```

静态门禁：

- PMD Maven Plugin 3.28.0 / PMD 7.17.0：全 Reactor 0 违规；
- Catalog SpotBugs：15 条诊断，Priority 1 为 0、Priority 2 为 12、
  Priority 3 为 3；
- Trade SpotBugs：68 条诊断，Priority 1 为 0、Priority 2 为 51、
  Priority 3 为 17。

前端在 Node.js 24.14.0 / pnpm 11.9.0 下执行 `pnpm check`：

```text
Foundation Vitest   34
Storefront Vitest   52
Admin Vitest         2
合计                88
Playwright E2E       6
```

两端类型检查、生产构建和关键页面 axe 检查同时通过。

## 9. 真实 MySQL/RocketMQ 验证

命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\tools\verify-m8-product-reviews.ps1 -SkipBuild -SkipNetworkPreflight
```

最终证据：

```text
backend/.run/m8-product-reviews-2026072423305673999c/verification.json
backend/.run/m8-product-reviews-2026072423305673999c/cleanup.json
```

真实脚本验证：

- `ShipmentSigned -> Trade COMPLETED -> OrderCompleted Outbox -> Catalog eligibility`；
- 当前顾客读取资格，跨账户读取和提交均返回 404；
- 8 路相同评价请求全部返回同一个评价 ID，MySQL 只有一条评价和一条汇总事实；
- 点赞重放后计数为 1；
- 平台回复、举报和审核审计各一条；
- 举报成立后评价为 `HIDDEN`，公开评价数和评分汇总归零；
- RocketMQ Proxy 停机时 Outbox 保持 `PENDING`，资格数保持 0；
- Proxy 恢复后 Outbox 变为 `PUBLISHED`，Catalog 资格收敛；
- Trade 未发布 Outbox 为 0。

清理结果：

```json
{
  "cleanupErrors": [],
  "residualPorts": 0,
  "residualJvms": 0,
  "rocketMqProxyRunning": true
}
```

文档收口门禁随后发现，旧版清理报告只统计端口、JVM 和命令返回值，没有反查 Broker
元数据；尽管 `deleteSubGroup` 返回 success，Catalog 的临时消费组仍残留为孤儿配置。
该唯一后缀消费组已精确删除，并再次确认两个临时 schema、隔离 Topic、两个消费组、
端口和 JVM 均无残留，RocketMQ Proxy 保持运行。

验证脚本现已补强：

- 停止消费者后，消费组使用有限次数的“删除、稳定等待、`getConsumerConfig` 反查”，
  处理 Broker 延迟心跳在第一次删除后重新创建组配置的竞态；
- 删除 Topic 后使用 `topicList` 反查；
- 最终 `cleanup.json` 额外记录 `residualRocketMqConsumerGroups` 和
  `residualRocketMqTopics`；
- 任何 Broker 元数据残留都会进入 `cleanupErrors`，不能再仅凭删除命令的 success
  输出宣称零残留。

补强后再次完整执行真实专项，业务九阶段和清理门禁均通过：

```text
backend/.run/m8-product-reviews-20260725001057fdfc7e/verification.json
backend/.run/m8-product-reviews-20260725001057fdfc7e/cleanup.json
```

新的清理证据为：

```json
{
  "cleanupErrors": [],
  "residualPorts": 0,
  "residualJvms": 0,
  "residualRocketMqConsumerGroups": [],
  "residualRocketMqTopics": [],
  "rocketMqProxyRunning": true
}
```

该运行目录名由本机文件时钟生成；项目计划基线日期仍按 2026-07-24 记录，不用运行
目录名推进里程碑日期。

## 10. 当前坐标

本批交付时 M8.1–M8.10 已完成，搜索和运营统计仍待后续闭环。评价媒体依然属于
后续内容能力，不把当前核心评价闭环描述成已经支持图片或视频。2026-07-25 当前
阶段状态见
[M8 全面审查、回归与毕业收口](68-m8-full-audit-and-graduation-20260725.md)：
搜索、运营统计和整体审查均已完成，M8 已关闭。
