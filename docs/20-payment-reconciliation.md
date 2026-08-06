# Payment 支付与退款对账

## 1. 目标与边界

Payment 对账只比较 `ecom_payment` 内由 Payment 拥有的状态、渠道流水、Outbox 和
原支付引用，发现不一致时写入 `reconciliation_record`；它不跨 Schema 查询 Trade、
Inventory 或 Fulfillment，也不自动修改资金状态、补造流水或重发成功事件。

对账是异常发现与恢复确认机制，不替代创建支付、退款回调、本地事务、唯一约束和 Outbox。

## 2. 对账规则

规则类型使用固定枚举语义，不把订单号、退款号或错误正文放进指标标签：

| Domain | Issue type | 检查 |
| --- | --- | --- |
| PAYMENT | `PAYMENT_SUCCESS_INCOMPLETE` | 成功支付必须有渠道交易号和支付时间 |
| PAYMENT | `PAYMENT_SUCCESS_TRANSACTION_MISMATCH` | 成功状态必须有渠道、交易号、金额完全匹配的成功流水 |
| PAYMENT | `PAYMENT_SUCCESS_EVENT_MISSING` | 成功状态必须有 `PaymentSucceeded` Outbox 事实 |
| PAYMENT | `PAYMENT_SUCCESS_TRANSACTION_UNEXPECTED` | 非成功支付不能存在成功流水 |
| REFUND | `REFUND_SUCCESS_INCOMPLETE` | 成功退款必须有渠道退款号和退款时间 |
| REFUND | `REFUND_SUCCESS_TRANSACTION_MISMATCH` | 成功状态必须有渠道、退款号、金额完全匹配的成功退款流水 |
| REFUND | `REFUND_SUCCESS_EVENT_MISSING` | 成功状态必须有 `RefundSucceeded` Outbox 事实 |
| REFUND | `REFUND_SUCCESS_TRANSACTION_UNEXPECTED` | 非成功退款不能存在成功退款流水 |
| REFUND | `REFUND_SOURCE_PAYMENT_MISMATCH` | 退款引用的原支付必须成功，且订单、用户、支付号和整单金额一致 |

规则查询不读取回调原文，不返回用户 ID、金额或渠道密钥。业务引用只出现在 `ADMIN` 专用问题列表中。

## 3. 问题生命周期

```text
首次发现 -> OPEN (occurrences = 1)
重复发现 -> OPEN (occurrences + 1, last_detected_at 更新)
事实恢复 -> RESOLVED (保留首次/最后发现时间与出现次数)
再次出现 -> OPEN (保留原记录并继续累计)
```

`domain + reference_no + issue_type` 是数据库唯一键。多实例同时扫描时不会创建重复问题记录。任务默认每 10 秒运行，单轮最多处理 500 条；配置最大允许 5000 条。

扫描查询会额外读取一条用于判断是否达到上限。如果当前发现或未关闭问题超过 `scan-limit`，本轮仍新增/刷新有界结果，但不自动关闭未出现在当前集合中的旧问题，避免把被截断的异常误判为已恢复。`saturated` 会写入任务日志，后续可据此调整容量或分片扫描。

成功事实查询使用 `(aggregate_id, event_type)` 索引。当前不能直接清除 Payment 已发布 Outbox 历史；未来增加归档/保留策略时，必须让对账查询同时读取不可变归档标记，避免把合法归档误判为成功事件缺失。

## 4. 运维入口与指标

管理员只读查询：

```http
GET /api/v1/payment/admin/reconciliation/issues?status=OPEN&limit=50
```

- `status` 只允许 `OPEN` 或 `RESOLVED`；
- `limit` 范围为 1 至 100；
- 普通顾客返回 `403`；
- 接口不提供“修复”“忽略”或“改为成功”参数。

Prometheus 指标：

```text
ecommerce.reconciliation.issue.open{service="payment-service"}
```

Grafana 运维看板显示未关闭总数；持续 30 秒大于零触发 `PlainJournalReconciliationIssueOpen` critical 告警。指标只用于发现，MySQL 问题记录和业务表仍是诊断依据。

## 5. 故障恢复方式

对账任务不猜测修复动作。操作人员先通过领域事实定位原因：

- 若只是诊断故障注入或可验证的数据迁移问题，应由原数据所有者恢复事实；下一轮扫描自动关闭问题。
- 若 Outbox 仍是 `PENDING`，由已有发布任务继续推进，不直接补写 `PUBLISHED`。
- 若渠道结果未知，继续等待或查询渠道，不能凭对账结果写 `SUCCESS`。
- 若需要重试退款派发，只能调用 [领域授权补偿命令](19-compensation-governance.md)，并满足其状态、幂等与审计约束。

跨服务视角已经由管理端通过各领域只读契约组合；中央工作台没有跨库更新权限。

## 6. 验证证据

- 2 个专用集成测试覆盖健康零误报、事件缺失、退款金额/流水不一致、重复扫描、恢复关闭、权限和指标。
- 真实中间件冒烟已验证 MySQL/Flyway、Gateway 鉴权、真实定时任务、`OPEN` 指标和
  事实恢复后的 `RESOLVED`，并清理测试记录和本轮 Java 进程。
- Prometheus、Alertmanager、Grafana 和规则配置已在专项观测脚本中验证，当前配置见
  [观测文档](18-observability-and-alerting.md)。
- 当前全仓测试与覆盖率数字见[验证摘要](verification-summary.md)。
