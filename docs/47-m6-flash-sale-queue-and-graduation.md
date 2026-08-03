# M6 秒杀排队、最终裁决与毕业报告

> 日期：2026-07-22  
> 状态：已完成  
> 范围：Marketing、Gateway、Trade、Inventory、RocketMQ、Redis、MySQL、Nacos，以及普通交易混合峰值

## 1. 完成边界

M6 没有建立第二套库存系统。Redis 只负责活动入口的固定配额、一人一次、稳定令牌和快速失败；Marketing MySQL 保存活动与接受事实，Inventory MySQL 仍是最终库存裁决者。

```text
Gateway 活动限流
  -> Marketing Redis Lua 准入
  -> Marketing 本地事务：flash_sale_admission + flash_sale_outbox_event
  -> 独立 RocketMQ Topic
  -> Trade 幂等消费：flash_sale_order_request
  -> Trade 创建 FLASH_SALE 订单
  -> Inventory MySQL 条件更新、唯一预占与流水
  -> Trade 结果 Outbox
  -> Marketing 幂等回写 ORDER_CREATED / FAILED
```

关键约束：

- 接受事实与 `FlashSaleAdmissionAccepted` Outbox 在 Marketing 同一本地事务提交；
- 秒杀与普通订单使用独立 Topic、消费组、发布器和 Trade 调度池；
- Trade 以 `request_token`、`admission_event_id`、请求哈希和订单来源引用防重；
- 可恢复异常保持 `PROCESSING` 并有限重试，耗尽后进入 `NEEDS_ATTENTION`；
- 成功或失败通过版本化事件回写 Marketing，连接异常不伪造终态；
- Redis 数据不是订单、支付或库存成功证明。

## 2. 自动化覆盖

新增测试覆盖：

- 活动创建、发布、时间窗与权限；
- 固定配额、一人一次、稳定请求令牌和幂等冲突；
- Redis 不可用时准入失败关闭；
- Marketing 接受事实、Outbox 发布重试与结果消费幂等；
- Trade 重复准入事件、唯一建单、库存拒绝、可恢复异常和终态事件；
- Marketing 结果回写、重复结果和不一致终态拒绝；
- 秒杀指标、线程池与调度恢复配置。

最终全量门禁为 54 份 Surefire 报告、204 个测试，0 失败、0 错误、0 跳过。

## 3. 第一道准入门禁

正式证据：

```text
backend/.run/m6-flash-sale-admission-final-20260722-r1
```

结果：

| 场景 | 结果 |
| --- | --- |
| 1000 请求 / 100 并发 / 100 名额 | 100 个 `202`，900 个 `409` |
| 吞吐与 P95 | 1033.87 RPS，268.46 ms |
| 同一用户 100 并发 | 100 个响应，共用一个稳定令牌，只占一个名额 |
| Gateway 100 请求 / 20 上限 | 20 个 `202`，80 个 `429` |
| Redis 停机 | 准入 7 ms 返回 `503`，普通活动查询 `200` |
| Redis 恢复 | 原活动重新返回 `202` |

该门禁证明入口配额和失败语义，不单独证明订单或库存成功。

## 4. 排队、最终裁决与 MQ 故障

正式证据：

```text
backend/.run/m6-flash-sale-queue-final-20260722-r4
runId: m6q20260722122822
```

基础压力：

| 指标 | 结果 |
| --- | ---: |
| 请求 / 并发 / 名额 | 1000 / 100 / 100 |
| `202` 准入 | 100 |
| `409` 售罄 | 900 |
| 传输与契约错误 | 0 |
| 吞吐 | 1188.30 RPS |
| P95 | 228.89 ms |

MQ 故障期间：

- 先停止 Trade，100 个准入全部保存在 Marketing，未发布 Outbox 为 100；
- 停止 RocketMQ Proxy 后额外接受 1 个请求；
- 该请求返回真实接受结果，Marketing `QUEUED=101`，未发布 Outbox 为 1；
- Trade 订单仍为 0，Inventory `on_hand=101,reserved=0`；
- 没有把 RocketMQ 不可用解释为已建单。

恢复 RocketMQ 与 Trade 后最终状态：

| 所有者域 | 最终事实 |
| --- | ---: |
| Marketing `QUEUED` | 0 |
| Marketing `ORDER_CREATED` | 101 |
| Marketing `FAILED / RESULT_UNKNOWN` | 0 / 0 |
| Marketing 未发布 Outbox | 0 |
| Trade `PROCESSING` | 0 |
| Trade `ORDER_CREATED` | 101 |
| Trade `FAILED / NEEDS_ATTENTION` | 0 / 0 |
| Trade 秒杀订单 | 101 |
| Inventory `on_hand / reserved` | 101 / 101 |

数据库事实、Outbox 状态和最终库存方程共同构成结论；应用日志只用于诊断，不作为唯一证据。

两次正式 M6 运行中的机器级网络预检返回码为 1，原因是当时
`D:\DevTools\Network\check-dev-network.ps1` 仍保留“恰好 6 个容器”的旧断言，而当前核心中间件为
7 个。专项随后直接验证了 MySQL、Redis、Nacos、RocketMQ NameServer/Broker/Proxy 和 MinIO，
且业务链全部通过。该机器级脚本已于 2026-07-22 修复为按名称验证 7 个必需容器，允许额外的
观测或实验容器；历史证据中的返回码保持原样，不回写伪造。详见
[本地开发网络](07-local-development-network.md)。

## 5. 普通交易资源水位

同一运行中执行 300 请求、30 并发混合峰值：

| 场景 | 请求 | 状态 | P95 |
| --- | ---: | --- | ---: |
| 普通下单查询 | 180 | 全部 `200` | 972.99 ms |
| 支付创建查询 | 60 | 全部 `200` | 791.33 ms |
| 退款查询 | 60 | 全部 `200` | 738.99 ms |
| 合计 | 300 | 0 传输/契约错误 | 873.21 ms |

总吞吐为 94.06 RPS。该结果证明本机缩比环境中秒杀链收敛期间普通交易边界仍可用，不外推为生产 SLO 或集群容量承诺。

## 6. 观测与恢复

Prometheus 已采集 Marketing 作为第五个目标。M6 新增：

- 秒杀处理中数量；
- 最老处理中年龄；
- 成功/失败完成速率；
- 预计排空时间；
- `NEEDS_ATTENTION` 数量；
- 队列停止排空、队列老化和人工处理告警。

最终观测复验为五个实时目标、四个规则组、16 条规则。关闭观测 profile 不改变任何业务事实。

## 7. 毕业结论与边界

M6 退出条件已经满足：

- 入口固定配额和一人一次成立；
- Redis 不保存最终库存；
- 独立 MQ 排队和本地 Outbox 可恢复；
- 重复消息只产生一个订单与一个库存预占；
- MQ 停机后事实可核对并最终排空；
- 普通下单、支付和退款在混合峰值中保持可用；
- 中间态、失败态、积压和人工处理均有查询与指标。

当前结论限于单机、单 RocketMQ Broker、代表性服务单实例和缩比负载。M6 没有证明公网攻击防护、多机房容灾、生产级 SLO 或无限水平扩展。下一阶段按计划进入 M7 数据规模化专项。
