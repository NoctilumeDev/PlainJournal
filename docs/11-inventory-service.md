# 库存服务

`inventory-service` 是库存最终事实所有者，端口为 `18103`，数据独占
`ecom_inventory` Schema。它管理仓库、现货、预占、确认、释放、过期、退货回补、
库存流水和 Outbox，不管理商品价格、订单状态或退款金额。

## 1. 数据模型

| 表 | 职责 |
| --- | --- |
| `warehouse` | 仓库与启停状态 |
| `inventory_balance` | 每仓每 SKU 的 `on_hand` 与 `reserved` |
| `stock_adjustment` | 人工调整命令及请求哈希，保证幂等 |
| `inventory_reservation` | 订单级预占状态与过期时间 |
| `inventory_reservation_item` | 一次预占中的 SKU 明细 |
| `stock_movement` | 不可变库存流水 |
| `inventory_return` | 以售后号唯一记录原确认预占的退货回补事实 |
| `outbox_event` | 与库存事务同提交的待发布事件 |
| `consumed_event` | 后续消费者幂等基础表 |
| `consumer_failure` | 退货验收消息的失败报文和补偿状态 |
| `reconciliation_record` | 库存所有者域异常的 `OPEN/RESOLVED` 生命周期台账 |

库存始终满足：

```text
available = on_hand - reserved
0 <= reserved <= on_hand
```

确认预占同时减少 `on_hand` 与 `reserved`；释放或过期只减少 `reserved`。

退货回补只接受 `ReturnInspected` 事件，并核对原订单预占已经 `CONFIRMED`、仓库和 SKU 明细一致。以 `afterSaleNo` 唯一，重复事件不会重复增加 `on_hand` 或写第二条库存流水；成功后通过 Outbox 发布 `ReturnStocked`。

## 2. 接口边界

| Method | Path | 权限 |
| --- | --- | --- |
| `GET` | `/api/v1/inventory/status` | Public |
| `GET` | `/api/v1/inventory/stocks/{skuId}` | Public |
| `POST` | `/api/v1/inventory/admin/warehouses` | `ADMIN` / `WAREHOUSE` |
| `GET` | `/api/v1/inventory/admin/warehouses` | `ADMIN` / `WAREHOUSE` |
| `POST` | `/api/v1/inventory/admin/stocks/adjustments` | `ADMIN` / `WAREHOUSE` |
| `GET` | `/api/v1/inventory/admin/warehouses/{warehouseId}/stocks/{skuId}` | `ADMIN` / `WAREHOUSE` |
| `GET` | `/api/v1/inventory/admin/reconciliation/issues` | 仅 `ADMIN` |
| `GET` | `/api/v1/inventory/internal/warehouses/{code}` | 服务身份 |
| `POST` | `/api/v1/inventory/internal/reservations` | 服务身份 |
| `GET` | `/api/v1/inventory/internal/reservations/{reservationNo}` | 服务身份 |
| `POST` | `/api/v1/inventory/internal/reservations/{reservationNo}/confirm` | 服务身份 |
| `POST` | `/api/v1/inventory/internal/reservations/{reservationNo}/release` | 服务身份 |
| `POST` | `/api/v1/inventory/internal/reservations/{reservationNo}/expire` | 服务身份 |

内部接口不接受管理员 JWT。`trade-service` 通过 Nacos 直连库存服务并附带内部服务身份，公共网关直接拒绝 `/internal/` 路径。当前共享令牌是本地开发基线，生产环境还需 mTLS、网络策略、密钥轮换与密钥管理系统。

进入浏览器的 Warehouse、Stock、Reservation 与 ReturnStock 响应已对 `warehouseId`、`skuId`、`userId` 等 Snowflake `Long` 做 DTO 局部 JSON string 序列化；请求仍接受十进制字符串。没有修改全局 ObjectMapper，也没有改变内部事件载荷。M4 权威结算由 Trade 在提交订单时同步调用内部库存预占，浏览器不直接访问内部接口；1000 请求竞争和提交后响应丢失恢复均已通过真实 MySQL/Nacos 链路复验。

## 3. 并发与幂等

- 预占由 MySQL 条件更新完成：只有 `on_hand - reserved >= quantity` 才增加 `reserved`。
- 多 SKU 按 SKU ID 排序执行，降低并发死锁概率；任一 SKU 不足时，同一事务释放本次已经临时预占的数量，结果为 `REJECTED`。
- `reservation_no` 唯一；相同编号与相同请求返回原结果，不重复扣减。
- 内部预占命令必须显式携带未来的 `expiresAt`；该时间与订单、仓库、SKU、数量共同进入请求哈希。
- 相同编号携带不同订单、仓库、过期时间、SKU 或数量时返回 `IDEMPOTENCY_CONFLICT`。
- 库存调整同样使用唯一 `movementNo` 与请求哈希防重。
- 定时任务扫描已过期的 `RESERVED` 记录，状态竞争由行锁与状态检查决定唯一结果。

普通库存没有依赖 Redis 扣减。M6 秒杀只在 Redis 做流量准入，最终仍调用同一套 MySQL 条件更新、预占唯一约束、库存流水和状态机，不存在 Redis 扣减后绕过数据库裁决的第二套库存事实。

## 4. Outbox 降级

库存变更与 `outbox_event` 在同一个本地事务提交。后台任务通过 RocketMQ 5 gRPC 端点 `127.0.0.1:18082` 发布到 `ecommerce-inventory-events`：

```text
业务事务提交 -> PENDING -> 发布器领取 -> PUBLISHED
                                  \-> 失败后回到 PENDING 并退避重试
```

RocketMQ 不可用时库存操作仍然成功，事件保留在 MySQL，恢复后自动补发。事件信封包含 `eventId`、事件类型、聚合信息、时间、生产者、追踪号、版本与 payload。

库存还订阅交易主题中的 `OrderPaid`。消费幂等记录与
`RESERVED -> CONFIRMED`、库存流水和库存 Outbox 在同一本地事务内提交；
重复事件不会再次扣减，RocketMQ 暂时不可用也不会阻止库存服务启动。

## 5. 所有者域对账

Inventory 每 10 秒在自己的 schema 内核对余额与有效预占、调整/预占/退货流水、预占状态事件、退货原确认预占和 `ReturnStocked` Outbox。发现的问题以固定类型写入 `reconciliation_record`，重复扫描只增加次数；事实恢复后标记 `RESOLVED`。

对账只负责发现和确认恢复，不跨 schema 查询 Trade、Payment 或 Fulfillment，也不自动修改 `on_hand`、`reserved`、预占状态、库存流水或 Outbox。详细规则见 [Inventory 库存与退货回补对账](21-inventory-reconciliation.md)。

## 6. 已验证基线

自动化测试覆盖：

- 库存 100，1000 个不同预占并发竞争，恰好 100 个成功、900 个拒绝。
- 同一预占号 20 路并发重试，只产生一条预占和一条有效流水。
- 幂等号参数冲突被拒绝。
- 多 SKU 部分失败不遗留预占。
- 确认、释放、过期均保持库存恒等式。
- RocketMQ 首次发送失败后事件保留，重试可发布。
- 顾客角色不能调用仓库或内部库存命令。
- Warehouse、Stock、Reservation 与 ReturnStock 的浏览器可见业务 ID 保持 JSON string。
- 重复 `OrderPaid` 只确认一次预占、只生成一条确认流水。
- 对账健康扫描无误报；余额错位和缺失 `ReturnStocked` 能幂等发现、只读展示并在事实恢复后关闭。
- M6 真实排队实验中，100 个常规准入和 MQ 停机期间额外接受的 1 个请求恢复后全部通过 MySQL 最终裁决；最终 `on_hand=101,reserved=101`，没有超卖、重复预占或遗留处理中记录。

真实中间件冒烟使用 MySQL、Nacos、Redis、RocketMQ、MinIO 与网关，验证 20 件库存面对
100 个并发请求时恰好 20 个 `RESERVED`、80 个 `REJECTED`，并验证确认、释放、
幂等重试与 Outbox 最终发布。最新复验还在 MySQL/Flyway V4 中临时隐藏已发布的
`ReturnStocked` 事实，确认定时任务产生 `RETURN_EVENT_MISSING`、指标变为非零，并在
恢复事实后自动关闭问题。最终裁决证据见
[M0-M8 三层工程验收](evidence/m0-m8-three-layer-acceptance-20260728.md)。
