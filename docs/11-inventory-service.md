# 库存服务

`inventory-service` 是项目的第一个高并发正确性业务切片，端口为 `18103`，数据独占 `ecom_inventory` schema。它管理仓库、现货、预占、确认、释放、过期、库存流水和 Outbox，不管理商品价格与订单状态。

## 1. 数据模型

| 表 | 职责 |
| --- | --- |
| `warehouse` | 仓库与启停状态 |
| `inventory_balance` | 每仓每 SKU 的 `on_hand` 与 `reserved` |
| `stock_adjustment` | 人工调整命令及请求哈希，保证幂等 |
| `inventory_reservation` | 订单级预占状态与过期时间 |
| `inventory_reservation_item` | 一次预占中的 SKU 明细 |
| `stock_movement` | 不可变库存流水 |
| `outbox_event` | 与库存事务同提交的待发布事件 |
| `consumed_event` | 后续消费者幂等基础表 |

库存始终满足：

```text
available = on_hand - reserved
0 <= reserved <= on_hand
```

确认预占同时减少 `on_hand` 与 `reserved`；释放或过期只减少 `reserved`。

## 2. 接口边界

| Method | Path | 权限 |
| --- | --- | --- |
| `GET` | `/api/v1/inventory/status` | Public |
| `GET` | `/api/v1/inventory/stocks/{skuId}` | Public |
| `POST` | `/api/v1/inventory/admin/warehouses` | `ADMIN` / `WAREHOUSE` |
| `GET` | `/api/v1/inventory/admin/warehouses` | `ADMIN` / `WAREHOUSE` |
| `POST` | `/api/v1/inventory/admin/stocks/adjustments` | `ADMIN` / `WAREHOUSE` |
| `GET` | `/api/v1/inventory/admin/warehouses/{warehouseId}/stocks/{skuId}` | `ADMIN` / `WAREHOUSE` |
| `GET` | `/api/v1/inventory/internal/warehouses/{code}` | 服务身份 |
| `POST` | `/api/v1/inventory/internal/reservations` | 服务身份 |
| `GET` | `/api/v1/inventory/internal/reservations/{reservationNo}` | 服务身份 |
| `POST` | `/api/v1/inventory/internal/reservations/{reservationNo}/confirm` | 服务身份 |
| `POST` | `/api/v1/inventory/internal/reservations/{reservationNo}/release` | 服务身份 |
| `POST` | `/api/v1/inventory/internal/reservations/{reservationNo}/expire` | 服务身份 |

内部接口不接受管理员 JWT。`trade-service` 通过 Nacos 直连库存服务并附带内部服务身份，公共网关直接拒绝 `/internal/` 路径。当前共享令牌是本地开发基线，生产环境还需 mTLS、网络策略、密钥轮换与密钥管理系统。

## 3. 并发与幂等

- 预占由 MySQL 条件更新完成：只有 `on_hand - reserved >= quantity` 才增加 `reserved`。
- 多 SKU 按 SKU ID 排序执行，降低并发死锁概率；任一 SKU 不足时，同一事务释放本次已经临时预占的数量，结果为 `REJECTED`。
- `reservation_no` 唯一；相同编号与相同请求返回原结果，不重复扣减。
- 相同编号携带不同订单、仓库、SKU 或数量时返回 `IDEMPOTENCY_CONFLICT`。
- 库存调整同样使用唯一 `movementNo` 与请求哈希防重。
- 定时任务扫描已过期的 `RESERVED` 记录，状态竞争由行锁与状态检查决定唯一结果。

普通库存没有依赖 Redis 扣减。未来秒杀可以在 Redis 做流量准入，但 MySQL 仍负责最终库存事实。

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

## 5. 已验证基线

自动化测试覆盖：

- 库存 100，1000 个不同预占并发竞争，恰好 100 个成功、900 个拒绝。
- 同一预占号 20 路并发重试，只产生一条预占和一条有效流水。
- 幂等号参数冲突被拒绝。
- 多 SKU 部分失败不遗留预占。
- 确认、释放、过期均保持库存恒等式。
- RocketMQ 首次发送失败后事件保留，重试可发布。
- 顾客角色不能调用仓库或内部库存命令。
- 重复 `OrderPaid` 只确认一次预占、只生成一条确认流水。

真实中间件冒烟使用 MySQL、Nacos、Redis、RocketMQ、MinIO 与网关，验证 20 件库存面对 100 个并发请求时恰好 20 个 `RESERVED`、80 个 `REJECTED`，并验证确认、释放、幂等重试与 Outbox 最终发布。
