# Inventory 库存与退货回补对账

## 1. 目标与边界

Inventory 对账只比较 `ecom_inventory` 内由库存服务拥有的余额、预占、调整、不可变流水、退货回补和 Outbox 事实。异常写入本地 `reconciliation_record`；任务不跨 schema 查询 Trade、Payment 或 Fulfillment，也不自动修改库存、补造流水或重发事件。

这是一条异常发现与恢复确认链，不替代 MySQL 条件更新、行锁、唯一约束、状态机、Outbox 或幂等消费。MySQL 仍是最终库存事实。

## 2. 固定对账规则

| Domain | Issue type | 不变量 |
| --- | --- | --- |
| BALANCE | `BALANCE_RESERVED_MISMATCH` | `reserved` 必须等于同仓同 SKU 的 `RESERVED` 预占明细之和 |
| ADJUSTMENT | `ADJUSTMENT_STUCK_PENDING` | 本地事务提交后不应遗留 `PENDING` 调整单 |
| ADJUSTMENT | `ADJUSTMENT_MOVEMENT_MISMATCH` | `APPLIED` 调整单必须有仓库、SKU、数量完全一致的 `ADJUSTMENT` 流水 |
| RESERVATION | `RESERVATION_STUCK_PENDING` | 本地事务提交后不应遗留 `PENDING` 预占 |
| RESERVATION | `RESERVATION_BALANCE_MISSING` | 有效预占的每个 SKU 必须存在余额行 |
| RESERVATION | `RESERVATION_MOVEMENT_MISMATCH` | `RESERVED/CONFIRMED/RELEASED/EXPIRED` 必须具有与状态和数量一致的流水链 |
| RESERVATION | `RESERVATION_EVENT_MISSING` | 已决预占状态必须存在对应 Outbox 事实 |
| RETURN | `RETURN_STATUS_INCOMPLETE` | 回补记录必须完成为 `STOCKED` 且具有 `stocked_at` |
| RETURN | `RETURN_SOURCE_RESERVATION_MISMATCH` | 回补必须引用同订单、同仓库的原 `CONFIRMED` 预占 |
| RETURN | `RETURN_MOVEMENT_MISMATCH` | 原预占每个 SKU 必须具有数量完全一致的 `RETURN` 流水 |
| RETURN | `RETURN_EVENT_MISSING` | 已回补退货必须存在 `ReturnStocked` Outbox 事实 |

规则类型固定，业务号只进入 MySQL 记录，不进入指标标签，避免高基数。当前不使用“最后一条流水猜测并覆盖余额”的自动修复方式；库存写入口仍只有原领域命令。

## 3. 扫描与问题生命周期

任务默认每 10 秒扫描一次，单轮最多处理 500 条，配置上限为 5000 条。`domain + reference_no + issue_type` 是唯一键，多实例并行扫描不会创建重复问题：

```text
首次发现 -> OPEN / occurrences=1
重复发现 -> 保持 OPEN / occurrences+1
事实恢复 -> RESOLVED / resolved_at
再次出现 -> 重新 OPEN / 保留首次发现历史
```

扫描会多取一条判断是否饱和。发现集或未关闭集超过限制时仍记录有界结果，但不关闭未出现在截断结果中的旧问题，避免错误宣告恢复。

## 4. 运维入口与告警

```http
GET /api/v1/inventory/admin/reconciliation/issues?status=OPEN&limit=50
```

- 仅 `ADMIN` 可读；`WAREHOUSE`、顾客和匿名请求均无权访问。
- `status` 仅允许 `OPEN` 或 `RESOLVED`，`limit` 为 1 至 100。
- 接口只返回固定问题类型、业务引用和时间，不暴露事件正文。

Prometheus 指标：

```text
ecommerce.reconciliation.issue.open{service="inventory-service"}
```

Grafana 与 Payment 共用所有者域问题面板；任一服务未关闭问题持续 30 秒会触发 `PlainJournalReconciliationIssueOpen` critical 告警。告警用于发现，MySQL 业务事实和问题台账用于诊断。

## 5. 恢复原则

- 对账结果不能直接触发库存加减，也不能把预占改成目标状态。
- Outbox 仍为 `PENDING` 时由既有发布器重试，不直接写 `PUBLISHED`。
- 需要恢复时必须先定位原命令、消费或迁移问题，通过领域拥有的幂等入口处理。
- 事实恢复后由下一轮扫描关闭问题；`RESOLVED` 历史保留用于审计。
- 当前中央治理工作区只聚合只读结果并调用领域 API，不拥有 `ecom_inventory` 写权限。

## 6. 验证证据

- 2 个专用集成测试覆盖健康零误报、余额错位、缺失退货事件、重复扫描、只读不修库存、权限、指标和恢复关闭。
- 真实中间件冒烟在 MySQL/Flyway 中临时把已发布 `ReturnStocked` 改为故障类型，
  真实定时任务产生 `RETURN_EVENT_MISSING` 和非零指标；恢复事件类型后问题自动进入
  `RESOLVED`。
- 同一轮验证确认 Prometheus、Alertmanager 和 Grafana 可观察问题生命周期，并在
  结束后清理测试记录、观测容器与本轮 Java 进程。
- 当前全仓测试与覆盖率数字见[验证摘要](verification-summary.md)。
