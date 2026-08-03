# M8 第十二批：运营统计事件读模型、对账与审计重建

## 1. 目标与边界

本批新增 `analytics-service`（18110），完成自营 B2C 运营统计的第一条独立闭环：

- 只消费版本化领域事件，不跨服务 JOIN 生产表；
- Analytics MySQL 保存来源事件日志、日汇总、商品汇总、重建审计和消费失败事实；
- 重复投递不重复累计，逻辑身份冲突进入失败治理；
- 汇总可以从本服务来源事件日志对账和重建；
- 管理端只展示已经落入 Analytics 所有者域的事实，不临时拼接 Trade、Payment 或
  Fulfillment 数据；
- M9 的 Go 服务仍保留为多商户阶段的异构读模型演进，不替代本批 Java 自营统计。

本批不实现推荐算法、实时数仓、跨商户结算报表、外部 BI、CDC、OLAP 集群或
 exactly-once 消息承诺。

## 2. 服务与事件边界

`analytics-service` 订阅两个独立 Topic，并只接受六类 `payloadVersion = 1` 事件：

| 来源 | 事件 | 当前统计用途 |
| --- | --- | --- |
| Trade | `OrderCreated` | 创建订单数、创建金额、独立顾客 |
| Trade | `OrderClosed` | 关闭订单数 |
| Trade | `OrderCompleted` | 完成订单、商品销量与有覆盖的商品实付收入 |
| Trade | `AfterSaleApplied` | 售后申请数与申请退款金额 |
| Payment | `PaymentSucceeded` | 支付笔数与支付金额 |
| Payment | `RefundSucceeded` | 成功退款笔数与退款金额 |

消费者不仅校验事件白名单、`payloadVersion` 和生产者，还把聚合身份绑定到载荷事实：
订单事件必须为 `TradeOrder/orderNo`，售后申请必须为
`AfterSaleOrder/afterSaleNo`，支付与退款必须分别为
`PaymentOrder/paymentNo`、`RefundOrder/refundNo`。身份不一致的消息先进入
`consumer_failure.NEEDS_ATTENTION` 再 ACK，不得污染运营投影。

消费者校验事件类型、生产者、聚合身份、版本、业务字段、金额精度和商品行。来源事件
按 `event_id` 主键去重，并以
`producer + aggregate_type + aggregate_id + aggregate_version + event_type`
建立逻辑唯一约束。同一身份、同一指纹视为重复；同一身份、不同指纹不能覆盖旧事实，
而是持久化到 `consumer_failure` 后进入人工关注语义。

Trade 的 `OrderCompleted` 商品行快照新增：

```text
lineAmount
discountAmount
payableAmount
```

新事件可以按不可变订单分摊快照统计商品实付收入。旧事件若没有 `payableAmount`，
Analytics 只累计销量和完成订单，商品收入保持未覆盖；不按标价、订单总额或比例进行
推测。

## 3. Analytics 所有者域

`ecom_analytics` 当前包含：

| 表 | 责任 |
| --- | --- |
| `analytics_projection_guard` | 消费、对账重建之间的全局投影互斥锁 |
| `analytics_source_event` | 已校验的来源事件和业务日期 |
| `analytics_source_product_line` | `OrderCompleted` 不可变商品行快照 |
| `analytics_daily_summary` | 按业务日期汇总的订单、支付、售后和退款统计 |
| `analytics_product_summary` | 按业务日期、商品汇总的销量和收入覆盖 |
| `analytics_rebuild_audit` | 幂等、带原因、带操作者的重建审计 |
| `consumer_failure` | 临时失败、终态失败和恢复事实 |

来源事件日志与汇总表在同一个 Analytics 本地事务内提交。MySQL 是该读模型的最终
事实；RocketMQ 只负责投递，管理端和 Prometheus 都不能反写统计结果。

## 4. 并发、幂等和故障语义

- 消费和重建都先锁定 `analytics_projection_guard`，避免多实例消费与重建互相覆盖。
- 消费事务先检查事件 ID 和逻辑身份，再插入来源日志、商品行并更新汇总。
- Broker 或 Proxy 不可用时不 ACK、不伪造累计；消息留在 Broker，恢复后继续消费。
- 无效契约和逻辑身份冲突先写 `consumer_failure`，再按终态策略 ACK，避免毒消息永久
  占用队列。
- 管理端重建命令使用稳定 `commandId`。同键同参数返回第一次持久化审计结果；同键
  不同参数返回 409。
- 重建只允许 `ADMIN` 或 `OPERATOR`，必须提供 8 至 500 字符原因；`WAREHOUSE`
  无权读取运营总览或执行治理命令。

## 5. 对账与重建

Analytics 从自己的来源事件日志重新计算期望日汇总和商品汇总，再与当前投影比较：

```text
MISSING  期望存在，当前投影缺失
STALE    主键存在，但计数、金额、销量或覆盖数不一致
ORPHAN   当前投影存在，但来源事件无法推导
```

对账具有行数上限并返回 `saturated`。达到上限时拒绝重建，不能把不完整扫描解释为
全量正确。正常重建流程为：

```text
权限与幂等校验
  -> 锁定投影
  -> 重建前对账
  -> 删除日期范围内派生汇总
  -> 从来源事件日志重算
  -> 重建后对账必须收敛为 0
  -> 追加 analytics_rebuild_audit
```

重建不修改 Trade、Payment 或任何其他服务的事实，也不直接消费生产表。

## 6. API、前端与观测

Gateway 新增 `/api/v1/analytics/**` 路由。主要接口为：

| 接口 | 权限 | 作用 |
| --- | --- | --- |
| `GET /api/v1/analytics/overview` | `ADMIN / OPERATOR` | 日期范围总览、日趋势、商品排行和投影新鲜度 |
| `GET /api/v1/analytics/admin/reconciliation` | `ADMIN / OPERATOR` | 有界投影对账 |
| `POST /api/v1/analytics/admin/rebuild` | `ADMIN / OPERATOR` | 幂等、审计式日期范围重建 |

管理端运营首页展示创建订单、支付、完成、关闭、售后、退款、独立顾客、商品销量、
商品收入覆盖和最后消费时间。仓库角色不会发起无权限的 Analytics 请求。

Prometheus 使用既有专用 `X-Metrics-Token` 采集身份。新增低基数指标覆盖事件接受与
重复、重建结果和对账问题数；订单号、用户 ID、事件 ID 和错误正文不进入标签。
固定观测 Compose 尚未增加 Analytics 常驻抓取和专属 Grafana 面板，因此本批只声明
应用指标契约和真实鉴权采集已经验证。

## 7. 自动化与静态门禁

本批交付时的代码阶段快照：

```text
mvn clean verify
90 份 Surefire 报告
318 tests
0 failures / 0 errors / 0 skipped

platform-common   14 tests
trade-service    104 tests
analytics-service  6 tests
chat-service      42 tests
```

全 Reactor PMD 3.28.0 / PMD 7.17.0 为 0 违规。Analytics SpotBugs 低阈值专项为
10 条诊断，其中 Priority 1 为 0、Priority 2 为 7、Priority 3 为 3；剩余告警属于
Spring/Jackson/JDBC 构造注入引用、消息边界宽异常和 SQL text block 换行诊断。
`ProjectionRows` 已改为不可变副本，消除了可变结果集泄漏。

上述 90 份报告和 318 tests 只记录 M8.12 交付时点，不是当前仓库最终基线。
2026-07-25 的 M8 整体收口快照为 97 份 Surefire 报告、399 tests；2026-07-28
进入 M9 前复审后的当前基线为 100 份报告、435 tests，0 失败/错误/跳过。

前端在 Node.js 24.14.0 / pnpm 11.9.0 下执行 `pnpm check`：

```text
Foundation Vitest   37
Storefront Vitest   52
Admin Vitest         2
Playwright E2E       6
```

两端类型检查和生产构建同时通过。

全量冷构建首轮还命中了 Chat Outbox 的真实精度边界：Java 纳秒时间写入
`TIMESTAMP(3)` 时可能向上舍入，立即执行的发布任务仍使用原纳秒值，导致刚写入事件
短暂不满足 `next_attempt_at <= now`。Chat 现已在 Outbox 持久化边界截断到毫秒，
并用固定纳秒时钟建立确定性回归测试；Chat 42 tests 与第二次全量门禁通过。该修复
不是给测试增加等待，也没有提前消费未来重试任务。

## 8. 真实中间件证据

入口：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\tools\verify-m8-analytics.ps1 -EvidenceDate 20260724
```

最终证据：

```text
backend/.run/m8-analytics-202607248c8eb758/verification.json
backend/.run/m8-analytics-202607248c8eb758/cleanup.json
```

真实 MySQL 8.4、Nacos、RocketMQ、Gateway 和单个 Analytics JVM 验证结果：

- 匿名总览 401、仓库角色 403、管理员 200；
- 8 次消息发送收敛为 7 条来源事件，重复 `OrderCreated` 只有 1 行；
- 创建订单 2、支付 1、完成 1、关闭 1、售后 1、退款 1、独立顾客 2；
- 有快照商品收入为 60.00；旧事件商品收入为 0 且覆盖订单数为 0；
- RocketMQ Proxy 停机期间来源事件数保持 6，恢复后补齐为 7；
- 注入 `DAILY:STALE`、`PRODUCT:MISSING`、`PRODUCT:ORPHAN` 三类偏差后，
  审计重建从 3 个问题收敛为 0；
- 同键重建返回第一次结果，同键冲突返回 409，重建审计只有 1 行；
- Prometheus 专用身份返回 200，事件、重建和对账指标均存在。

清理证据为临时 schema 0、临时用户 0、应用端口 0、应用 JVM 0、临时 Topic 0、
清理错误 0；七个核心中间件容器全部保持运行。

M8 整体审查随后发现，首版清理报告没有反查运行隔离消费组。旧验证组
`analytics-202607248c8eb758` 确实仍留在 Broker，现已精确删除并验证不存在。脚本已
补为有限重试删除、稳定等待和 `getConsumerConfig` 反查，并把
`residualConsumerGroups` 纳入 `cleanup.json`；数据库 schema/账号、端口、JVM、
Topic、消费组或七个核心容器数量任一不符合预期，都会让验证失败。

验证脚本首轮前置检查发现 Nacos 用户名被错误当作 `.env` 密钥，已统一为仓库固定的
非敏感用户名 `nacos`；第二轮发现 1 秒 RocketMQ 长轮询会触发 Proxy `40018`
deadline 不足，已恢复为项目验证基线 5 秒。两次失败都安全关闭并验证零残留。

## 8.1 2026-08-03 浏览器身份合同补强

V6 管理首页收口时发现 `ProductSummary.productId` 与 `RebuildView.operatorId` 仍以
JSON number 暴露。M7 分布式 ID 已超过 JavaScript 安全整数边界，因此对外身份现统一
使用 Jackson `ToStringSerializer`；Foundation 对应类型改为 `BusinessId`。

Analytics MySQL 列、Java 内部 `long`、事件聚合、对账和重建逻辑均未改变。后端
`AnalyticsFlowIntegrationTest` 5/5 通过，并新增 HTTP JSON 字符串断言；管理首页
entity 与 Chromium 使用 19 位商品 ID 完成逐字符验证。完整前端证据见
[V6.4.4 管理首页与 V6 收口](101-frontend-visual-v6-4-4-operations-home-20260803.md)。

## 9. 阶段结论

M8.1–M8.12 的十二个机制切片已经全部实现并分别获得自动化或真实中间件证据。
2026-07-25 已继续完成 M8 全目录、死代码、依赖、文档、前端、事件契约和真实脚本
整体审查，并修正 Chat 消费失败恢复所有权。M8 当前已经毕业；M9 三个商户和 Go
异构统计读模型的技术候选门禁已满足，但仍按用户要求冻结，必须等待用户复审后
单独确认进入。完整结论见
[M8 全面审查、回归与毕业收口](68-m8-full-audit-and-graduation-20260725.md)。
