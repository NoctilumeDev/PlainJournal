# 数据所有权

## 1. 本地数据库策略

本地开发先共用一个 MySQL 8.4 实例，但按服务拆分 schema。将来拆实例时不改变业务所有权。

| 服务 | Schema | 核心表 |
| --- | --- | --- |
| identity | `ecom_identity` | 已实现：`user_account`、`user_address`、`identity_role`、`user_role`、`refresh_token`、`login_record`；后续：`user_profile`、`permission` |
| catalog | `ecom_catalog` | 已实现：`catalog_category`、`catalog_brand`、`product_spu`、`product_sku`、`product_media`；后续：`price_history`、`review_eligibility`、`product_review`、`review_reply`、`review_like`、`review_report` |
| inventory | `ecom_inventory` | 已实现：`warehouse`、`inventory_balance`、`stock_adjustment`、`inventory_reservation`、`inventory_reservation_item`、`stock_movement`、`outbox_event`、`consumed_event` |
| trade | `ecom_trade` | 已实现：`cart_item`、`trade_order`、`order_item`、`order_address_snapshot`、`order_benefit_selection`、`order_price_snapshot`、`order_discount_allocation`、`order_status_history`、`outbox_event`、`consumed_event`；后续：`after_sale_order`、`after_sale_item`、`after_sale_history` |
| payment | `ecom_payment` | 已实现：`payment_order`、`payment_transaction`、`payment_callback_log`、`outbox_event`、`consumed_event`；后续：`refund_order`、`reconciliation_record` |
| fulfillment | `ecom_fulfillment` | 已实现：`fulfillment_order`、`fulfillment_status_history`、`logistics_trace`、`outbox_event`、`consumed_event`；后续：`package`、`return_receipt`、签收证明对象引用 |
| marketing | `ecom_marketing` | 已实现：`marketing_rule`、`marketing_rule_region`、`user_benefit`、`pricing_lock`、`pricing_lock_benefit`、`pricing_lock_allocation`、`consumed_event`；后续：`seckill_activity`、`seckill_sku` |
| chat | `ecom_chat` | `conversation`、`conversation_member`、`chat_message`、`message_receipt`、`chat_attachment` |
| notification | `ecom_notification` | `notification_task`、`notification_delivery`、`in_app_notification` |

每个 schema 还应拥有自己的 `outbox_event`、`consumed_event` 和必要的补偿任务表。

## 2. 所有权规则

- 只有数据所属服务可以写该表。
- 其他服务通过 API 获取即时数据，通过领域事件维护必要的只读副本。
- 不建立跨 schema 外键，不执行跨服务 JOIN。
- 订单项保存 SKU 名称、规格、成交单价和图片键快照，不依赖商品表还原历史订单。
- identity 保存用户可编辑地址；trade 在下单事务中保存独立地址快照，fulfillment 从 `OrderPaid` 复制配送快照。用户修改或删除源地址不能影响历史订单与履约。
- 交易服务发布订单完成事件，商品服务据此生成可评价凭据；评价服务逻辑不能直接查询交易表。
- 售后申请和审核归交易服务，渠道退款归支付服务，退货验收入库归履约服务。
- 聊天只保存业务上下文的类型和 ID，不复制订单全部内容。

## 3. 标识与业务编号

- 数据库主键使用全局唯一 `BIGINT`，首期可采用 MyBatis-Plus `ASSIGN_ID`。
- 订单号、支付单号、退款单号、预占号和运单号是独立业务编号，并建立唯一索引。
- 第三方回调同时保存平台业务编号与渠道编号，渠道编号必须唯一。
- 事件 ID、请求幂等键和客户端消息 ID 使用 UUID/ULID 字符串，不与展示编号混用。

## 4. 通用字段约定

```text
id                  BIGINT
version             INT             # 乐观锁
created_at          TIMESTAMP(3)
updated_at          TIMESTAMP(3)
created_by          BIGINT NULL
updated_by          BIGINT NULL
```

- 金额使用 `DECIMAL(18,2)`，Java 使用 `BigDecimal`，禁止 `double`。
- 数量使用整数并校验上限，禁止负库存。
- API 时间使用 ISO-8601；服务内部使用 `Instant`。
- 订单、支付、库存流水和审计记录不做物理删除。
- 商品等可恢复内容使用明确状态或软删除，查询条件必须统一封装。

## 5. Redis 数据归属

| Key 前缀 | 所有者 | 用途 | 最终事实 |
| --- | --- | --- | --- |
| `identity:` | identity | 会话、验证码、登录限流 | 否 |
| `catalog:` | catalog | 商品与类目缓存 | 否 |
| `cart:` | trade | 购物车快速读写 | MySQL 定期/关键节点持久化 |
| `stock:` | inventory | 热点库存视图、秒杀准入 | 否 |
| `chat:` | chat | 在线状态、节点路由、未读计数 | 消息正文以 MySQL 为准 |
| `geo:` | fulfillment | 最新配送坐标 | 关键轨迹以 MySQL 为准 |

Key 必须包含环境和业务前缀；缓存对象必须有 TTL 或明确淘汰策略。

## 6. MinIO 对象归属

| Bucket | 所有者 | 访问规则 |
| --- | --- | --- |
| `product-media` | catalog | 对外展示通过签名 URL 或受控 CDN |
| `user-avatars` | identity | 上传者和后台审核可写 |
| `chat-attachments` | chat | 仅会话成员可下载 |
| `review-media` | catalog | 发布后按内容权限读取 |
| `logistics-proofs` | fulfillment | 顾客仅可查看自己的订单证明 |
| `after-sale-evidence` | trade/售后子域 | 顾客与有权限员工可访问 |

数据库只保存对象键、大小、哈希、MIME、上传者和引用状态。任何下载都重新校验业务权限，不能仅凭对象键访问。

## 7. 隐私与审计

- 密码仅保存强哈希；令牌、支付密钥和第三方凭据不进数据库明文字段。
- 手机号、邮箱和地址在普通后台列表中脱敏。
- 财务、权限、退款、库存调整和客服导出操作必须审计。
- 日志禁止输出密码、完整令牌、银行卡号和完整地址。
