# 履约与物流服务

`fulfillment-service` 端口为 `18106`，数据独占 `ecom_fulfillment` schema。当前切片实现支付后自动建履约单、仓库拣货/打包/发货、追加式物流轨迹、签收、异常登记、Outbox 和消费幂等。多包裹、真实承运商回调验签、签收证明与退货收货留给后续切片。

## 1. 数据边界

| 表 | 职责 |
| --- | --- |
| `fulfillment_order` | 订单唯一履约单、不可变配送地址、承运商、运单号和当前状态 |
| `fulfillment_status_history` | 每次状态迁移、命令、原因和操作者 |
| `logistics_trace` | 不可覆盖的物流节点、位置和外部事件幂等信息 |
| `outbox_event` | 与履约状态同事务提交的领域事件 |
| `consumed_event` | `OrderPaid` 消费幂等记录 |

履约服务不读取 identity、交易、支付或库存数据库。它只消费交易服务发布的 `OrderPaid`，事件包含订单固化的配送地址；`order_no` 唯一，因此重复投递不会创建第二张履约单。用户后续编辑或删除 identity 地址不会改变履约目的地。

## 2. 接口与权限

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/fulfillment/status` | 公共 | 服务状态 |
| `GET` | `/api/v1/fulfillment/orders` | 登录用户 | 当前用户履约列表 |
| `GET` | `/api/v1/fulfillment/orders/{orderNo}` | 订单所有者 | 物流详情与时间线 |
| `GET` | `/api/v1/fulfillment/admin/orders` | ADMIN / WAREHOUSE | 按状态查看履约任务 |
| `POST` | `/admin/orders/{no}/picking` | ADMIN / WAREHOUSE | 开始拣货 |
| `POST` | `/admin/orders/{no}/packed` | ADMIN / WAREHOUSE | 完成打包 |
| `POST` | `/admin/orders/{no}/ship` | ADMIN / WAREHOUSE | 绑定唯一运单并发货 |
| `POST` | `/admin/orders/{no}/traces` | ADMIN / WAREHOUSE | 模拟追加承运商节点 |
| `POST` | `/admin/orders/{no}/exception` | ADMIN / WAREHOUSE | 登记履约异常 |

当前轨迹接口用于本地模拟和后台联调。接真实承运商后应新增独立回调入口，使用渠道签名、时间窗和来源校验，而不是把仓库 JWT 方案暴露给承运商。

## 3. 状态与一致性

```text
CREATED -> PICKING -> PACKED -> SHIPPED -> IN_TRANSIT -> DELIVERING -> SIGNED
              \-> EXCEPTION                 \-> EXCEPTION
```

- 状态只能通过明确命令迁移，不能使用通用字段更新接口跳级。
- `carrier + tracking_no` 唯一；`carrier + tracking_no + external_event_id` 保证物流回调幂等。
- 外部事件号重复且内容相同直接返回当前结果；内容不同返回 `IDEMPOTENCY_CONFLICT`。
- `FulfillmentCreated`、`ShipmentDispatched`、`ShipmentSigned` 经 Outbox 发布到 `ecommerce-logistics-events`。
- 交易服务幂等消费后推进 `PAID -> FULFILLING -> SHIPPED -> COMPLETED`。乱序事件不确认消息，消费记录和状态更新一起回滚，等待重投。
- MQ 不可用不影响本地履约命令提交；Outbox 保留并重试，交易端暂时显示较早状态。

## 4. 已验证基线

- 重复 `OrderPaid` 只创建一张履约单和一个 `FulfillmentCreated`。
- 交易地址快照随 `OrderPaid` 固化到履约单，源地址更新或删除后仍保持原配送信息。
- 非法跳过拣货/打包会被拒绝。
- 重复轨迹只保存一次，篡改同一外部事件号会产生幂等冲突。
- 顾客不能操作仓库接口，也不能读取其他用户的履约信息。
- Outbox 首次发送失败后保留，下一次可成功发布。
- 真实 MySQL/Nacos/RocketMQ 链路已完成支付、库存确认、履约创建、拣货、打包、发货、运输、派送、签收和订单 `COMPLETED` 的完整收敛。
