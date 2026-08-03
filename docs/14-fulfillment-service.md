# 履约与物流服务

`fulfillment-service` 端口为 `18106`，数据独占 `ecom_fulfillment` schema。当前切片实现支付后自动建履约单、仓库拣货/打包/发货、追加式物流轨迹、MySQL 最新位置与空间附近查询、可重建 Redis GEO 投影、签收、异常登记，以及整单退货的寄回、收货和验收。多包裹、真实承运商回调验签、实时 GPS、外部地图与签收证明仍留在后续切片。

## 1. 数据边界

| 表 | 职责 |
| --- | --- |
| `fulfillment_order` | 订单唯一履约单、不可变配送地址、承运商、运单号和当前状态 |
| `fulfillment_status_history` | 每次状态迁移、命令、原因和操作者 |
| `logistics_trace` | 不可覆盖的物流节点、位置和外部事件幂等信息 |
| `shipment_latest_position` | 每张履约单最新位置的 MySQL 所有者域投影，包含十进制坐标、`POINT SRID 4326` 和轨迹引用 |
| `return_receipt` | 售后单唯一的退货收货单、寄回物流和验收状态 |
| `return_item` | 从售后批准事件复制的退货商品与可退金额快照 |
| `return_status_history` | 寄回、收货、验收的追加式历史 |
| `outbox_event` | 与履约状态同事务提交的领域事件 |
| `consumed_event` | 正向订单和售后批准事件消费幂等记录 |
| `consumer_failure` | 订单/售后消息的有限重试、终态失败与恢复记录 |
| `reconciliation_record` | 履约/退货所有者域问题的 `OPEN/RESOLVED` 历史台账 |

履约服务不读取 identity、交易、支付或库存数据库。它只消费交易服务发布的 `OrderPaid`，事件包含订单固化的配送地址；`order_no` 唯一，因此重复投递不会创建第二张履约单。用户后续编辑或删除 identity 地址不会改变履约目的地。

## 2. 接口与权限

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/fulfillment/status` | 公共 | 服务状态 |
| `GET` | `/api/v1/fulfillment/orders` | 登录用户 | 当前用户履约列表 |
| `GET` | `/api/v1/fulfillment/orders/{orderNo}` | 订单所有者 | 物流详情与时间线 |
| `GET` | `/api/v1/fulfillment/orders/{orderNo}/position` | 订单所有者 | 读取最新位置；无位置返回 404 |
| `GET` | `/api/v1/fulfillment/admin/orders` | ADMIN / WAREHOUSE | 按状态查看履约任务 |
| `POST` | `/admin/orders/{no}/picking` | ADMIN / WAREHOUSE | 开始拣货 |
| `POST` | `/admin/orders/{no}/packed` | ADMIN / WAREHOUSE | 完成打包 |
| `POST` | `/admin/orders/{no}/ship` | ADMIN / WAREHOUSE | 绑定唯一运单并发货 |
| `POST` | `/admin/orders/{no}/traces` | ADMIN / WAREHOUSE | 模拟追加承运商节点 |
| `POST` | `/admin/orders/{no}/exception` | ADMIN / WAREHOUSE | 登记履约异常 |
| `GET` | `/api/v1/fulfillment/returns`、`/{no}` | CUSTOMER / ADMIN | 顾客查看自己的退货单 |
| `POST` | `/api/v1/fulfillment/returns/{no}/shipment` | CUSTOMER / ADMIN | 顾客提交寄回物流 |
| `GET/POST` | `/api/v1/fulfillment/admin/returns/**` | ADMIN / WAREHOUSE | 仓库查询、收货和验收 |
| `GET` | `/api/v1/fulfillment/admin/reconciliation/issues` | ADMIN | 按状态只读查询履约/退货对账问题 |
| `GET` | `/api/v1/fulfillment/admin/geo/nearby` | ADMIN / WAREHOUSE | 使用 MySQL 空间事实执行有界附近查询 |
| `POST` | `/api/v1/fulfillment/admin/geo/cache/rebuild` | ADMIN | 从 MySQL 最新位置投影重建 Redis GEO |

当前轨迹接口用于本地模拟和后台联调。接真实承运商后应新增独立回调入口，使用渠道签名、时间窗和来源校验，而不是把仓库 JWT 方案暴露给承运商。

## 3. 状态与一致性

```text
CREATED -> PICKING -> PACKED -> SHIPPED -> IN_TRANSIT -> DELIVERING -> SIGNED
              \-> EXCEPTION                 \-> EXCEPTION
```

- 状态只能通过明确命令迁移，不能使用通用字段更新接口跳级。
- `carrier + tracking_no` 唯一；`carrier + tracking_no + external_event_id` 保证物流回调幂等。
- 外部事件号重复且内容相同直接返回当前结果；内容不同返回 `IDEMPOTENCY_CONFLICT`。
- 带坐标轨迹与 `shipment_latest_position` 在同一 MySQL 本地事务提交；先比较 `occurred_at`，相同时间再比较 `trace_id`，迟到旧事件不能覆盖新位置。
- MySQL 通过 `POINT SRID 4326`、空间索引和 `ST_Distance_Sphere` 裁决附近查询；Redis GEO 不拥有物流事实，也不改变查询结果。
- Redis 只在事务提交后最佳努力更新。顾客读取缓存缺失、过期或异常时回退 MySQL 并尝试读修复；管理员可执行有界重建，重建失败返回明确 503。
- `FulfillmentCreated`、`ShipmentDispatched`、`ShipmentSigned` 经 Outbox 发布到 `ecommerce-logistics-events`。
- 交易服务幂等消费后推进 `PAID -> FULFILLING -> SHIPPED -> COMPLETED`。乱序事件不确认消息，消费记录和状态更新一起回滚，等待重投。
- MQ 不可用不影响本地履约命令提交；Outbox 保留并重试，交易端暂时显示较早状态。
- `AfterSaleApproved` 按售后号幂等创建一张退货单；顾客、仓库和其他用户的权限边界独立校验。
- 验收成功发布 `ReturnInspected`，但不直接写库存；库存服务消费事件后独立裁决回补。

## 4. 已验证基线

- 重复 `OrderPaid` 只创建一张履约单和一个 `FulfillmentCreated`。
- 交易地址快照随 `OrderPaid` 固化到履约单，源地址更新或删除后仍保持原配送信息。
- 非法跳过拣货/打包会被拒绝。
- 重复轨迹只保存一次，篡改同一外部事件号会产生幂等冲突。
- 顾客不能操作仓库接口，也不能读取其他用户的履约信息。
- Outbox 首次发送失败后保留，下一次可成功发布。
- 真实 MySQL/Nacos/RocketMQ 链路已完成支付、库存确认、履约创建、拣货、打包、发货、运输、派送、签收和订单 `COMPLETED` 的完整收敛。
- 重复售后批准不会创建第二张退货单；重复寄回、收货或验收命令不会追加重复副作用。
- 无效版本和持续失败的售后消息会进入 `consumer_failure`，不永久阻塞消费组。
- 所有者域对账覆盖履约历史、状态时间戳、生命周期事件、签收轨迹，以及退货历史、时间戳、商品行金额和生命周期事件；问题只进入本地 `OPEN/RESOLVED` 台账和指标，不自动修复履约事实。详见 [Trade 与 Fulfillment 所有者域对账](25-trade-fulfillment-reconciliation.md)。
- M4 顾客端已接通履约时间线和确认收货。真实故障代理在 Fulfillment 上游返回 HTTP 200 后断开响应，页面查询恢复 `SIGNED`，Trade 经 `ShipmentSigned` 最终收敛为 `COMPLETED`；跨账户查询返回 404。详见 [M4 履约与物流时间线](39-m4-fulfillment-and-logistics-timeline.md)。
- `OrderPaid` 地址事件使用独立内部载荷。消费者同时接受正整数 JSON number 和历史十进制字符串 ID；解析坏载荷进入 `NEEDS_ATTENTION` 并 ACK，业务异常有限重试。
- M8.9 真实 MySQL/Redis 验证覆盖 V8/V9 Flyway、`POINT SRID 4326`、空间索引、南京/上海/乱序苏州轨迹、MySQL 附近查询、Redis 删除和暂停时回退、读修复、管理员重建、顾客跨账户 404 及最终业务夹具/Redis Key/18106/JVM 零残留。详见 [M8 第九批：Fulfillment 物流 GEO 与可重建 Redis 投影](64-m8-fulfillment-geo.md)。
