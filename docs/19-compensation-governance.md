# 领域授权补偿与审计

## 1. 本批目标

M2 第五批把“日志里看到失败后手工改库”收敛为领域所有者提供的受控命令。首个代表切片选择 Payment 的退款渠道派发：它已经具有稳定 `refundNo`、MySQL 状态机、有限自动重试和明确的 `NEEDS_ATTENTION`，适合证明授权、幂等、并发串行化、审计和恢复，而不需要中央服务跨库写数据。

这不是任意 RocketMQ 消息重放器，也不是一个可以把退款直接改成成功的超级管理员接口。渠道成功仍只能由验签后的独立退款回调确认。

## 2. 领域边界

```text
管理员 -> Payment 补偿命令 -> ecom_payment 本地事务
                              |- 锁定 refund_order
                              |- 校验可恢复状态
                              |- 重置为待派发
                              `- 追加 retry audit

退款派发任务 -> 同一 refundNo 请求渠道 -> 独立验签回调 -> 最终退款事实
```

- Payment 只读写自己的 schema，不读取 Trade、Inventory 或 Fulfillment 表。
- 管理员只请求领域动作，不能指定目标数据库状态。
- MySQL 是命令结果和审计的最终事实；Redis、Grafana 和日志都不参与裁决。
- 状态变更与审计插入在同一个本地事务提交，任何一方失败都会整体回滚。

## 3. 退款派发重试契约

```http
POST /api/v1/payment/admin/refunds/{refundNo}/retry-dispatch
Authorization: Bearer <ADMIN JWT>
Idempotency-Key: <stable command id>
Content-Type: application/json

{"reason":"Channel connectivity restored"}
```

仅允许以下两种状态：

| 退款状态 | 派发状态 | 含义 | 结果 |
| --- | --- | --- | --- |
| `PROCESSING` | `NEEDS_ATTENTION` | 自动派发已耗尽 | 重置为 `PROCESSING/PENDING` |
| `FAILED` | `SENT` | 渠道明确返回失败，已经人工调查 | 重置为 `PROCESSING/PENDING` |

`PENDING` 已在自动队列、`REQUESTING` 正在调用、`SENT` 结果未知、`SUCCESS` 已完成；这些状态均拒绝新重试，避免重复资金副作用。

命令身份由 `Idempotency-Key` 唯一约束。请求哈希覆盖 `refundNo`、管理员 JWT subject 和去除首尾空白后的原因：

- 同一键、同一内容只返回当前退款事实，不再次重置派发状态；
- 同一键、不同内容返回 `IDEMPOTENCY_CONFLICT`；
- 两个不同管理员命令并发操作同一退款时，通过 `SELECT ... FOR UPDATE` 串行化，最多一个能进入可恢复状态转换；
- 被领域规则拒绝的有效管理员命令同样追加审计，再返回 `409`。

## 4. 审计事实

`refund_dispatch_retry_audit` 是追加式审计表，不保存渠道原始报文、回调密钥或客户身份。每条记录包含：

- 命令 ID、请求哈希、退款号；
- 管理员 subject 与必填原因；
- `ACCEPTED` / `REJECTED` 结果和错误码；
- 操作前后的退款状态、派发状态、派发次数，以及重置前的最后错误摘要；
- 服务端时间。

管理员可只读查询：

```http
GET /api/v1/payment/admin/refunds/{refundNo}/retry-dispatch/audits?limit=50
```

查询限定 1 至 100 条，仅访问 Payment 本地表。普通顾客不能执行重试，也不能读取审计。

## 5. 验证标准

- H2 服务集成测试覆盖未认证、顾客越权、管理员成功、非法状态、命令键冲突、重复命令和失败退款恢复。
- 并发测试同时提交两个管理员命令，断言一条 `ACCEPTED`、一条 `REJECTED`，退款只重置一次。
- 全链路冒烟在真实 MySQL 中注入“自动派发耗尽”故障，经 Gateway 调用领域命令，等待真实定时任务重新派发，并核对接受/拒绝审计。
- 重试后仍保持 `PROCESSING`；只有独立签名回调才能产生 `RefundSucceeded`。

当前证据：退款集成测试覆盖并发命令一条接受、一条拒绝；退款调度整类连续复跑消除了 `TIMESTAMP(3)` 与纳秒时钟边界导致的偶发未到期判断。最近一次全量 `mvn clean verify` 共 130 个测试，0 失败、0 错误、0 跳过。八服务真实中间件冒烟已在 MySQL/Flyway V7、Gateway、管理员 JWT 和真实定时派发任务上验证上述故障注入与收敛过程，并在结束后清理测试审计记录。

## 6. 后续边界

Payment 的[支付与退款只读对账](20-payment-reconciliation.md)、Inventory 的[库存与退货回补只读对账](21-inventory-reconciliation.md)，以及 [Trade/Fulfillment 所有者域对账](25-trade-fulfillment-reconciliation.md)均已实现。后续只按真实恢复需求增加最小授权动作；只有确实存在稳定业务键、幂等副作用和明确恢复状态的操作才允许人工重试。补偿工作台未来只聚合各领域只读视图并调用领域 API，不拥有跨 schema 更新权限。

不提供“任意消息正文重放”“任意状态修改”“跳过验签确认资金成功”或“中央服务直接修复多库”的能力。
