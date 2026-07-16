# 营销价格服务

`marketing-service` 端口为 `18107`，独占 `ecom_marketing` schema。本切片实现优惠券、红包、补贴三类固定金额权益，地区资格、订单价格锁、精确分摊以及取消释放/支付核销；满减、限时活动和秒杀仍属于后续切片。

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

营销服务不写订单或库存表。交易服务不读取营销数据库，只通过内部接口锁价并把结果复制为不可变订单快照。

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

内部锁价接口以 `orderNo + requestHash` 判定幂等：相同请求返回原锁，不同内容复用订单号返回冲突。营销响应丢失时，交易恢复任务使用同一订单号重试，不会重复占用权益。

`OrderPaid`、`OrderCanceled`、`OrderClosed` 来自交易 Outbox。营销服务把 `consumed_event` 与核销/释放放在同一本地事务中；重复 MQ 消息没有重复副作用。旧订单没有营销锁时事件作为兼容空操作消费。

## 4. 接口

| Method | Path | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/marketing/admin/rules` | ADMIN/OPERATOR 创建规则 |
| `POST` | `/api/v1/marketing/admin/benefits` | ADMIN/OPERATOR 幂等发放用户权益 |
| `GET` | `/api/v1/marketing/benefits` | 当前用户查看权益和状态 |
| `POST` | `/api/v1/marketing/internal/pricing-locks` | 仅 trade-service 锁价 |
| `GET` | `/api/v1/marketing/internal/pricing-locks/orders/{orderNo}` | 查询订单价格锁 |
| `POST` | `/api/v1/marketing/internal/pricing-locks/orders/{orderNo}/release` | 幂等释放 |
| `POST` | `/api/v1/marketing/internal/pricing-locks/orders/{orderNo}/redeem` | 幂等核销 |

## 5. 已验证基线

- 三类权益叠加后分类优惠、总优惠和实付严格一致。
- 地区、门槛、用户归属和同类型重复选择均被拒绝。
- 每份权益及全部权益的逐分分摊都能精确回汇总。
- 同一订单重复锁价只生成一个锁；同一权益不能同时锁给不同订单。
- 取消/缺货关闭释放权益，支付成功核销权益，重复事件只消费一次。
- 营销暂不可用时订单保持 `PENDING_STOCK` 且不调用库存；恢复后按原订单号继续。
