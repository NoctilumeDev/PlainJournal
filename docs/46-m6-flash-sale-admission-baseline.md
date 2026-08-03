# M6 第一批：秒杀活动准入基线

> 验证日期：2026-07-22  
> 状态：第一批已完成，M6 整体仍在进行  
> 主范围：Marketing 活动事实、Gateway 独立限流、Redis Lua 原子准入、结果查询、真实并发与 Redis 故障

## 1. 本批结论

M6 已建立独立于普通下单的秒杀准入入口，但尚未进入排队建单和库存最终裁决。

本批完成：

- Marketing 在 MySQL 保存秒杀活动事实，支持 `DRAFT -> ACTIVE`；
- 发布活动前必须成功预热 Redis，失败时 MySQL 保持 `DRAFT`；
- Gateway 对活动准入路径使用独立 `flash-sale` 限流策略；
- Redis Lua 原子完成活动时间校验、固定配额、一人一次和请求幂等；
- 首次接受生成稳定请求令牌，重复请求返回原令牌和原接受时间；
- 查询结果按 JWT 用户隔离，跨用户统一返回 `404`；
- Redis 不可用时 Marketing 明确返回 `503 FLASH_SALE_ADMISSION_UNAVAILABLE`，不做本地放行；
- 真实 Redis 重启后准入恢复，普通活动查询在故障期间保持可用。

本批没有完成：

- RocketMQ 秒杀 Topic 与排队削峰；
- Trade 消费令牌并创建秒杀订单；
- Inventory 基于 MySQL 条件更新做最终库存裁决；
- 排队中、建单成功、失败和超时释放的持久化结果状态；
- 秒杀消费者与普通交易线程池、连接池和 Topic 隔离；
- MQ 积压、预计清空时间、死信告警和普通交易影响对比。

因此不能把本报告表述为“M6 已完成”，也不能把 Redis 准入解释为购买成功或库存扣减。

## 2. 所有权与状态边界

```text
MySQL / Marketing
  flash_sale_activity：活动名称、SKU、活动价、准入上限、时间窗和状态

Redis / Marketing
  短期活动门闩、用户令牌、幂等请求映射和查询快照

Gateway
  按来源 IP 的活动入口限流

Inventory / MySQL
  尚未接入本批；后续仍是库存最终裁决者
```

活动发布顺序：

```text
锁定 MySQL DRAFT 活动
  -> Redis 预热成功
  -> MySQL 更新为 ACTIVE
```

若 Redis 预热失败，事务回滚并返回 `503`。若 Redis 已预热但 MySQL 更新失败，活动仍不是 `ACTIVE`；准入服务会先检查 MySQL 活动事实，因此 Redis 残留数据不能越过活动状态。

活动到期由 `startsAt/endsAt` 时间窗裁决。本批没有提供活动关闭或 Redis 数据丢失后的授权重建命令。

## 3. API

| Method | Path | 权限 | 语义 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/marketing/admin/flash-sales` | ADMIN/OPERATOR | 创建 `DRAFT` 活动 |
| `POST` | `/api/v1/marketing/admin/flash-sales/{activityNo}/publish` | ADMIN/OPERATOR | 预热 Redis 并发布 |
| `GET` | `/api/v1/marketing/flash-sales/{activityNo}` | Public | 查询活动事实 |
| `POST` | `/api/v1/marketing/flash-sales/{activityNo}/admissions` | CUSTOMER/ADMIN | 使用 `Idempotency-Key` 提交准入，成功返回 `202` |
| `GET` | `/api/v1/marketing/flash-sales/admissions/{requestToken}` | 令牌所有者 | 查询接受结果 |

主要失败语义：

| HTTP | Code | 含义 |
| --- | --- | --- |
| `409` | `FLASH_SALE_NOT_STARTED` | 活动未开始 |
| `409` | `FLASH_SALE_SOLD_OUT` | 准入配额耗尽 |
| `410` | `FLASH_SALE_ENDED` | 活动已结束 |
| `503` | `FLASH_SALE_NOT_READY` | 活动尚未发布或门闩未就绪 |
| `503` | `FLASH_SALE_ADMISSION_UNAVAILABLE` | Redis 准入不可用 |
| `404` | `RESOURCE_NOT_FOUND` | 活动、令牌不存在或令牌不属于当前用户 |

## 4. Redis 原子边界

Key 使用环境命名空间：

```text
ecommerce:{APP_ENV}:marketing:flash-sale:activity:{activityNo}:meta
ecommerce:{APP_ENV}:marketing:flash-sale:activity:{activityNo}:user:{userId}
ecommerce:{APP_ENV}:marketing:flash-sale:activity:{activityNo}:request:{sha256(userId:idempotencyKey)}
ecommerce:{APP_ENV}:marketing:flash-sale:token:{requestToken}
```

Lua 在一次执行内完成：

1. 同请求键重放；
2. 同活动同用户令牌重放；
3. 活动元数据、状态和时间窗校验；
4. `remaining > 0` 校验；
5. `remaining - 1` 与 `admitted + 1`；
6. 用户、请求键和结果快照写入。

用户、请求和令牌 TTL 覆盖“活动结束时间 + 结果保留期”，不是从请求时刻简单保留固定 24 小时。Redis 不保存最终库存，也不产生订单事实。

## 5. Gateway 并发缺陷与修复

真实限流验证首次出现配置上限 20、实际只放行 19。审查确认原实现同时递增本地 Caffeine 窗口与 Redis 窗口，再取两个结果的交集。

并发下两套计数的到达顺序可能不同：

```text
请求 A：本地第 20 个，Redis 第 21 个
请求 B：本地第 21 个，Redis 第 20 个
```

两者都会被拒绝，造成无意义的准入损失。

修复后：

- Redis 启用且健康时只以 Redis 原子结果为准；
- Redis 禁用或调用失败时才启用有界本地窗口；
- Redis 恢复后清除对应本地窗口，避免旧降级状态继续影响全局裁决。

回归测试分别证明健康 Redis 结果不再与独立本地窗口取交集，以及 Redis 失败时本地窗口仍按上限拒绝。

## 6. 自动化门禁

本批新增或扩展测试覆盖：

- Gateway 活动路径独立策略；
- Redis 健康与本地降级的裁决边界；
- H2/Flyway 活动表和 `DRAFT -> ACTIVE` 状态推进；
- 管理、顾客、公开读取和令牌查询权限；
- 同请求键和同用户不同请求键返回稳定令牌；
- 60 个并发请求争抢 20 个名额，不超准入；
- 跨用户令牌查询返回 `404`；
- Redis 关闭配置下发布和准入均返回 `503`。

最终执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn clean verify
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
mvn -q com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs `
  '-Dspotbugs.effort=Max' '-Dspotbugs.threshold=Low' '-Dspotbugs.xmlOutput=true'
```

结果：

- 52 份 Surefire 报告、187 个测试，0 失败、0 错误、0 跳过；
- PMD 9 份报告、0 违规；
- SpotBugs 9 份报告、Priority 1 为 0。

## 7. 真实 MySQL、Redis、Nacos 与 Gateway 验证

执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
./tools/verify-m6-flash-sale-admission.ps1 -EnableRedisFaultInjection
```

正式证据目录：

```text
backend/.run/m6-flash-sale-m620260722083237
```

固定配额：

| 指标 | 结果 |
| --- | ---: |
| 总请求 | 1000 |
| 并发 | 100 |
| 活动准入上限 | 100 |
| `202` | 100 |
| `409` 售罄 | 900 |
| 传输/契约错误 | 0 |
| 吞吐 | 1611.58 RPS |
| P95 | 113.76 ms |
| P99 | 128.28 ms |
| Redis `admitted` | 100 |
| Redis `remaining` | 0 |

其他断言：

- 同一用户 100 个并发请求全部返回 `202`，Redis 只记录 1 次接受；
- 不同幂等键返回相同请求令牌和原始 `acceptedAt`；
- Gateway 上限 20 时精确得到 20 个 `202`、80 个 `429`；
- 停止 Redis 后准入在 11 ms 返回 `503 FLASH_SALE_ADMISSION_UNAVAILABLE`；
- Redis 故障期间普通活动查询返回 `200`；
- Redis 恢复后原活动重新返回 `202`；
- Nacos 配置来源为 `nacos`；
- 脚本最终删除本次 MySQL 活动与 Redis 命名空间键，并释放 18000/18107；
- Redis 最终为 `running/healthy`。

执行当时网络诊断因机器级脚本保留旧的“恰好 6 个容器”断言而返回 1；专项随后显式验证 MySQL、Redis、Nacos、RocketMQ NameServer/Broker/Proxy 和 MinIO 共 7 个容器，未修改路由、网卡、代理或 Docker 数据。该机器级脚本已于 2026-07-22 修复为按名称验证 7 个必需容器并允许额外容器，历史返回码保持原样。

## 8. 下一批

M6 第二批应在本准入令牌上继续，而不是另建一套秒杀入口：

1. 定义版本化 `FlashSaleAdmissionAccepted` 事实和独立 RocketMQ Topic；
2. Marketing 使用本地事实/Outbox 保证接受结果可恢复，不能只依赖 Redis 快照；
3. Trade 幂等消费并创建秒杀订单，结果保持可查询；
4. Inventory 使用 MySQL 条件更新、唯一约束和流水做最终库存裁决；
5. 建立排队、成功、失败、超时和释放状态；
6. 隔离秒杀与普通交易的消费者线程池、连接池和容量水位；
7. 量化 MQ 积压、清空时间以及普通下单、支付和退款的延迟影响。

Redis 数据丢失后的准入重建、已接受令牌持久化和最终结果恢复是第二批的进入重点；在这些事实落地前，M6 不能毕业。

> 后续状态：以上第二批能力已于 2026-07-22 完成并通过真实 RocketMQ 停机恢复、Trade 建单、Inventory MySQL 最终裁决和混合峰值验证。最终结论见 [M6 秒杀排队、最终裁决与毕业报告](47-m6-flash-sale-queue-and-graduation.md)。
