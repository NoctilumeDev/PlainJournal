# 营销价格服务

`marketing-service` 端口为 `18107`，独占 `ecom_marketing` schema。当前实现优惠券、红包、补贴三类固定金额权益，地区资格、订单价格锁、精确分摊、取消释放/支付核销，以及完整 M6 秒杀活动、Redis 准入、MySQL 接受事实、Outbox 排队和最终结果查询。

## 1. 领域边界

| 表 | 职责 |
| --- | --- |
| `marketing_rule` | 权益类型、门槛、固定优惠、叠加顺序和有效期 |
| `marketing_rule_region` | 可用省/市/区的六位行政区划编码；无记录表示全国可用 |
| `user_benefit` | 用户权益及 `AVAILABLE -> LOCKED -> REDEEMED` 生命周期 |
| `pricing_lock` | 以订单号幂等保存原价、优惠、实付和锁状态 |
| `pricing_lock_benefit` | 本次价格锁实际使用的规则和权益快照 |
| `pricing_lock_allocation` | 每份权益按订单项精确到分的分摊结果 |
| `consumed_event` | 订单生命周期事件消费幂等记录 |
| `flash_sale_activity` | 秒杀活动、SKU、活动价、准入上限、时间窗和 `DRAFT/ACTIVE` 事实 |
| `flash_sale_admission` | 已接受用户、地址、稳定令牌、排队/终态结果和失败码 |
| `flash_sale_outbox_event` | 与接受事实同事务提交的 `FlashSaleAdmissionAccepted` 事件 |

营销服务不写订单或库存表。交易服务不读取营销数据库，只通过内部接口锁价并把结果复制为不可变订单快照。

顾客侧 `POST /api/v1/marketing/pricing-previews` 复用同一套地区资格、门槛、叠加和逐行分摊算法，但不创建 `pricing_lock`，也不把 `user_benefit` 从 `AVAILABLE` 改为 `LOCKED`。试算结果是当前输入下的只读计算，不是成交承诺。

## 2. 叠加与地区规则

- 单笔订单每种类型最多选择一份权益，`COUPON`、`RED_PACKET`、`SUBSIDY` 可以同时使用。
- 每条规则按订单原价校验使用门槛，并按 `stackOrder` 顺序应用。
- 有地区记录的规则，只要配送地址的对应省、市或区编码命中任一允许区域即可使用。
- 总优惠不会超过订单原价；每一份优惠基于各订单项剩余金额按比例分摊。
- 分摊先计算整数分，再按最大余数和稳定行号补齐，保证所有行分摊严格回汇总优惠且任何行不为负。

## 3. 锁定与事件生命周期

```text
AVAILABLE --下单锁价--> LOCKED --OrderPaid--> REDEEMED
                              \
                               --OrderCanceled / OrderClosed--> AVAILABLE
```

内部锁价接口以 `orderNo + requestHash` 判定幂等：相同请求返回原锁，不同内容复用订单号返回冲突。营销响应丢失时，交易恢复任务使用同一订单号重试，不会重复占用权益。Trade 仅在这一业务幂等、唯一约束和可恢复状态均成立的前提下对锁价开放最多两次总尝试，并使用独立超时预算、熔断和并发舱壁；详见 [关键同步调用韧性](22-synchronous-call-resilience.md)。

`OrderPaid`、`OrderCanceled`、`OrderClosed` 来自交易 Outbox。营销服务把 `consumed_event` 与核销/释放放在同一本地事务中；重复 MQ 消息没有重复副作用。旧订单没有营销锁时事件作为兼容空操作消费。

## 4. 接口

| Method | Path | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/marketing/admin/rules` | ADMIN/OPERATOR 创建规则 |
| `POST` | `/api/v1/marketing/admin/benefits` | ADMIN/OPERATOR 幂等发放用户权益 |
| `GET` | `/api/v1/marketing/benefits` | 当前用户查看权益和状态 |
| `POST` | `/api/v1/marketing/pricing-previews` | 当前用户无副作用试算价格与逐行分摊 |
| `POST` | `/api/v1/marketing/internal/pricing-locks` | 仅 trade-service 锁价 |
| `GET` | `/api/v1/marketing/internal/pricing-locks/orders/{orderNo}` | 查询订单价格锁 |
| `POST` | `/api/v1/marketing/internal/pricing-locks/orders/{orderNo}/release` | 幂等释放 |
| `POST` | `/api/v1/marketing/internal/pricing-locks/orders/{orderNo}/redeem` | 幂等核销 |
| `POST` | `/api/v1/marketing/admin/flash-sales` | ADMIN/OPERATOR 创建秒杀活动 |
| `POST` | `/api/v1/marketing/admin/flash-sales/{activityNo}/publish` | 预热 Redis 并推进到 `ACTIVE` |
| `GET` | `/api/v1/marketing/flash-sales/{activityNo}` | 公开查询活动 |
| `POST` | `/api/v1/marketing/flash-sales/{activityNo}/admissions` | 当前用户使用 `Idempotency-Key` 请求准入 |
| `GET` | `/api/v1/marketing/flash-sales/admissions/{requestToken}` | 当前用户查询自己的准入结果 |

## 5. 已验证基线

- 三类权益叠加后分类优惠、总优惠和实付严格一致。
- 顾客试算与正式锁价使用同一计算规则；自动化验证试算不写 `pricing_lock`、不改变权益状态，且正式锁价金额与试算一致。
- 地区、门槛、用户归属和同类型重复选择均被拒绝。
- 每份权益及全部权益的逐分分摊都能精确回汇总。
- 同一订单重复锁价只生成一个锁；同一权益不能同时锁给不同订单。
- 取消/缺货关闭释放权益，支付成功核销权益，重复事件只消费一次。
- 没有营销价格锁的历史订单收到 `OrderPaid`、`OrderCanceled` 或 `OrderClosed` 时按兼容空操作消费；`consumed_event` 正常提交，重复投递仍保持幂等，不会因内层 `RESOURCE_NOT_FOUND` 把事务标记为 rollback-only 并永久阻塞后续消息。
- 营销暂不可用时订单保持 `PENDING_STOCK` 且不调用库存；恢复后按原订单号继续。
- 真实停服突发中，首笔失败由专用订单恢复线程在 6.33 秒后再次推进，最少五个逻辑失败打开 Trade 熔断，第 5 笔订单的锁价调用在本地拒绝；Marketing 恢复后 5 笔订单严格生成 5 个不同锁号，没有重复锁或重复权益副作用。
- M4 第三批真实 Gateway 链路使用 `COUPON-10` 把 ¥378.00 试算为 ¥368.00；调用前后 `pricing_lock` 均为 0，当前用户价格锁为 0，权益保持 `AVAILABLE`。Benefit 用户 ID 与试算分摊 SKU ID 均以 JSON string 返回。
- M4 顾客端已提供当前账户权益中心；管理端按 ADMIN/OPERATOR 开放规则创建和幂等权益发放。规则创建没有额外幂等键，也没有按 `ruleCode` 读取的管理端 API，响应丢失后不能用重复 POST 或后续 409 归因原命令成功；页面保留完整载荷并保持结果未知。权益发放由 `(user_id, grant_key)` 唯一约束裁决，同顾客、同键、同规则返回原权益，同键换规则返回 `IDEMPOTENCY_CONFLICT`，因此前端只允许原顾客、原规则、原 `grantKey` 重试。后端仍无规则/权益管理列表，页面只展示命令返回的权威结果，不伪造全量管理事实。
- M6 第一批真实验证中，1000 请求/100 并发严格得到 100 次准入和 900 次售罄，Redis 元数据为 `admitted=100`、`remaining=0`；同一用户 100 并发只消耗 1 个名额。
- Redis 准入不可用时发布或请求准入返回 `503`，不做本地成功；公开活动查询不依赖 Redis。Redis 接受后，Marketing 在本地事务内持久化 `QUEUED` 事实与 Outbox，准入令牌仍不是订单或库存成功，后续由 Trade 和 Inventory/MySQL 裁决。
- Trade 通过同一独立 Topic 返回 `FlashSaleOrderSucceeded/Failed`；Marketing 使用 `consumed_event` 幂等更新 `ORDER_CREATED/FAILED`。MQ 停机期间 Outbox 保留，恢复后 101 条准入全部收敛为 `ORDER_CREATED`，未发布、`QUEUED`、`FAILED` 和 `RESULT_UNKNOWN` 均为 0。

完整边界与证据见 [M6 第一批：秒杀活动准入基线](46-m6-flash-sale-admission-baseline.md)和 [M6 秒杀排队、最终裁决与毕业报告](47-m6-flash-sale-queue-and-graduation.md)。
