# Trade 与 Fulfillment 所有者域对账

## 1. 边界与原则

Trade 和 Fulfillment 各自在自己的 schema 内扫描事实、保存问题生命周期并暴露只读结果。对账不跨 schema 查询，不自动补事件、改状态或重放消息；MySQL 仍是最终事实，问题恢复后只把原记录从 `OPEN` 转为 `RESOLVED`。

两个服务使用相同的治理约束：

- `(domain, reference_no, issue_type)` 唯一，重复扫描只增加次数并更新时间；
- 扫描结果和已有 OPEN 记录均受 `scan-limit` 限制，结果饱和时不贸然关闭未出现在本页的问题；
- `ADMIN` 只能通过领域 API 查询 1 至 100 条 `OPEN/RESOLVED` 记录；
- `ecommerce.reconciliation.issues{service,status}` 使用有界标签，数据库读取失败不伪造成零问题；
- 定时任务、表和告警均由领域所有者维护，不存在中央跨库修复器。

## 2. Trade 检查项

Trade 对账覆盖：

- 订单当前状态与最后一条 `order_status_history`；
- 订单金额与不可变价格快照、商品行原价/优惠/实付合计；
- `PENDING_PAYMENT`、`PAYMENT_CONFIRMING`、`PAID`、`FULFILLING`、`SHIPPED`、
  `COMPLETED` 所需的生命周期 Outbox 与恢复事实；
- 售后当前状态与最后一条历史、完成时间/退款号、商品行可退金额合计；
- `AfterSaleApproved`、`RefundRequested`、`AfterSaleCompleted` 等售后状态事件。

只读入口：

```http
GET /api/v1/trade/admin/reconciliation/issues?status=OPEN&limit=50
```

## 3. Fulfillment 检查项

Fulfillment 对账覆盖：

- 履约当前状态与最后一条状态历史；
- 拣货、打包、发货、签收状态对应的时间戳、承运商和运单号；
- `FulfillmentCreated`、`ShipmentDispatched`、`ShipmentSigned` Outbox 事实；
- `SIGNED` 履约单是否存在时间一致的签收轨迹；
- 退货当前状态与最后一条历史、寄回/收货/验收时间、商品行可退金额；
- `ReturnReceiptCreated`、`ReturnShipmentSubmitted`、`ReturnReceived`、`ReturnInspected` 事件。

只读入口：

```http
GET /api/v1/fulfillment/admin/reconciliation/issues?status=OPEN&limit=50
```

## 4. 验证证据

自动测试分别覆盖正常无问题、缺失事实打开问题、重复扫描不重复建档、恢复后关闭问题，以及非管理员越权拒绝。

真实基础交易冒烟在完整正向/逆向交易完成后：

1. 删除本轮 Trade 的 `OrderCompleted` Outbox 事实，等待 `ORDER_STATE_EVENT_MISSING` 进入 `OPEN` 并核对 gauge；恢复原事实后等待同一问题转为 `RESOLVED`。
2. 删除本轮 Fulfillment 的 `ShipmentSigned` Outbox 事实，等待 `FULFILLMENT_STATE_EVENT_MISSING` 进入 `OPEN` 并核对 gauge；恢复原事实后等待同一问题转为 `RESOLVED`。

同轮 Payment、Inventory 对账也完成相同的“注入缺失→OPEN→恢复→RESOLVED”验证，因此 M2 已形成 Payment、Inventory、Trade、Fulfillment 四个最终事实所有者的只读对账闭环。
