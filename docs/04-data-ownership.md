# 数据所有权

## 1. 本地数据库策略

本地开发先共用一个 MySQL 8.4 实例，但按服务拆分 schema。将来拆实例时不改变业务所有权。

| 服务 | Schema | 核心表 |
| --- | --- | --- |
| identity | `ecom_identity` | 已实现：`user_account`、`user_address`、`identity_role`、`user_role`、`refresh_token`、`login_record`；后续：`user_profile`、`permission` |
| catalog | `ecom_catalog` | 已实现：`catalog_category`、`catalog_brand`、`product_spu`、`product_sku`、`product_media`、`review_eligibility`、`product_review`、`product_review_summary`、`review_reply`、`review_like`、`review_report`、`review_moderation_audit`、`catalog_search_outbox`、`catalog_search_recovery_audit`、`catalog_search_rebuild`、`catalog_search_rebuild_recovery_audit`、`catalog_search_reconciliation`、`consumed_event`、`consumer_failure`；后续：`price_history`、评价媒体 |
| inventory | `ecom_inventory` | 已实现：`warehouse`、`inventory_balance`、`stock_adjustment`、`inventory_reservation`、`inventory_reservation_item`、`inventory_return`、`stock_movement`、`reconciliation_record`、`outbox_event`、`consumed_event`、`consumer_failure` |
| trade | `ecom_trade` | 已实现：`cart_item`、`trade_order`、`order_item`、地址/价格/优惠快照表、`order_status_history`、`after_sale_order`、`after_sale_item`、`after_sale_history`、`reconciliation_record`、`outbox_event`、`consumed_event`、`consumer_failure` |
| payment | `ecom_payment` | 已实现：支付单/流水/回调表、`refund_order`、`refund_transaction`、`refund_callback_log`、`refund_dispatch_retry_audit`、`reconciliation_record`、`outbox_event`、`consumed_event`、`consumer_failure` |
| fulfillment | `ecom_fulfillment` | 已实现：正向履约/状态/轨迹表、`shipment_latest_position`、`return_receipt`、`return_item`、`return_status_history`、`reconciliation_record`、`outbox_event`、`consumed_event`、`consumer_failure`；后续：`package`、签收证明对象引用 |
| marketing | `ecom_marketing` | 已实现：`marketing_rule`、`marketing_rule_region`、`user_benefit`、`pricing_lock`、`pricing_lock_benefit`、`pricing_lock_allocation`、`consumed_event`；后续：`seckill_activity`、`seckill_sku` |
| chat | `ecom_chat` | 已实现：`chat_conversation`、`conversation_member`、`chat_message`、`message_receipt`、`chat_attachment`、`chat_attachment_upload`、`chat_attachment_scan_retry_audit`、`outbox_event`、`consumer_failure` |
| notification | `ecom_notification` | 已实现：`notification_recipient`、`notification_task`、`in_app_notification`、`notification_delivery`、`notification_delivery_retry_audit`、`consumed_event`、`consumer_failure` |
| analytics | `ecom_analytics` | 已实现：`analytics_projection_guard`、`analytics_source_event`、`analytics_source_product_line`、`analytics_daily_summary`、`analytics_product_summary`、`analytics_rebuild_audit`、`consumer_failure` |

每个 schema 还应拥有自己的 `outbox_event`、`consumed_event` 和必要的补偿任务表。

## 2. 所有权规则

- 只有数据所属服务可以写该表。
- 其他服务通过 API 获取即时数据，通过领域事件维护必要的只读副本。
- 不建立跨 schema 外键，不执行跨服务 JOIN。
- 订单项保存 SKU 名称、规格、成交单价和图片键快照，不依赖商品表还原历史订单。
- identity 保存用户可编辑地址；trade 在下单事务中保存独立地址快照，fulfillment 从 `OrderPaid` 复制配送快照。用户修改或删除源地址不能影响历史订单与履约。
- Trade 在订单完成事务中写入带不可变订单行快照的 `OrderCompleted` Outbox；Catalog
  幂等消费后生成评价资格。资格查询、评价提交和审核只使用 Catalog 本地事实，不能
  同步回查 Trade，也不能直接查询交易表。
- `review_eligibility` 按 `order_no + line_no` 唯一并保存顾客所有者；同一资格最多
  生成一条评价。`product_review_summary` 只统计 `PUBLISHED`，审核隐藏评价时与
  `review_moderation_audit`、举报状态和汇总扣减在同一 Catalog 本地事务提交。
- `product_spu.search_revision` 与 `catalog_search_outbox` 是 Catalog MySQL 的搜索
  推进事实。OpenSearch 文档、物理索引和别名只是派生投影，可以删除并从 MySQL
  全量重建；搜索结果必须回读 MySQL，只展示仍为 `ACTIVE` 的商品。
- 搜索对账只比较 Catalog MySQL 与当前索引版本，记录 `MISSING / STALE / ORPHAN`
  生命周期。自动修复只能新增同库 Outbox，不能把 OpenSearch 反写为商品事实；扫描
  达到上限时只修复双方共同完整覆盖的 ID 区间，禁止把未扫描尾部误判为孤儿。
- 售后申请、审核、价格分摊快照和最终完成归交易服务；退货寄回与仓库验收归履约服务；库存服务只依据验收事件幂等回补；渠道退款归支付服务。
- Payment、Inventory、Trade、Fulfillment 分别在自己的 schema 内运行只读对账并保存 `OPEN/RESOLVED` 问题生命周期；任何对账任务都不能跨 schema 修复或替最终事实所有者下结论。
- Fulfillment 的 `logistics_trace` 保存全部追加轨迹；`shipment_latest_position` 保存同一
  schema 内的最新位置投影，按轨迹发生时间和稳定轨迹 ID 裁决。Redis 不拥有任何物流
  最终事实。
- 聊天只保存业务上下文的类型和 ID，不复制订单全部内容。
- Chat 在自己的 schema 内保存附件上传意图、确认元数据、完整 SHA-256、扫描租约、
  扫描结论、有限重试状态、管理员重扫审计、消息绑定和孤儿清理状态；二进制对象归
  MinIO，Redis、RocketMQ 和 Outbox 不保存附件正文或对象键。
- ClamAV 只给出某次内容扫描结论，不拥有附件业务状态；`READY / INFECTED /
  SCAN_NEEDS_ATTENTION` 的最终事实归 Chat MySQL。
- Notification 在自己的 schema 内保存来源事件幂等事实、渲染后的通知任务、站内信
  已读状态、邮件偏好、邮件投递租约/尝试/错误、稳定 `Message-ID` 和管理员恢复审计。
  Payment、Fulfillment、RocketMQ 与 SMTP 均不能直接修改这些状态。
- SMTP 只表示外部传输边界。连接失败或响应未知时 Notification 保留
  `RETRY / NEEDS_ATTENTION`；邮件投递失败不能回滚已提交的站内信，也不能改变订单、
  支付、退款或履约事实。
- Analytics 只消费 Trade/Payment 版本化事件并拥有自己的来源事件日志和派生汇总。
  `analytics_source_event` 是该读模型的重建来源，但不能替代 Trade/Payment 最终事实；
  日汇总和商品汇总可以删除后重建，不能反向修改生产者 schema。
- 旧 `OrderCompleted` 若没有商品行 `payableAmount`，Analytics 只累计销量与完成订单，
  商品收入保持未覆盖；不得用标价、订单总额或比例估算。
- Analytics 对账只比较本服务来源日志与本服务汇总，记录
  `MISSING / STALE / ORPHAN`。重建必须有角色、稳定命令 ID、原因和追加式审计，
  达到扫描上限或重建后未收敛时失败关闭。

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
| `ecommerce:{environment}:chat:` | chat | 带 TTL 的节点租约与用户在线节点路由 | 否；消息正文、回执和未读事实均以 MySQL 为准 |
| `ecommerce:{environment}:fulfillment:geo:` | fulfillment | Redis GEO 最新位置加速和元数据 | 否；全部轨迹与最新位置投影以 Fulfillment MySQL 为准，可重建 |

Key 必须包含环境和业务前缀；缓存对象必须有 TTL 或明确淘汰策略。

## 5.1 OpenSearch 数据归属

OpenSearch 由 Catalog 管理，但不属于最终事实数据库：

- 只保存公开检索需要的商品、类目、品牌和 SKU 文本以及 `search_revision`；
- 增量写使用 MySQL 搜索 Outbox 和外部版本，旧任务不能覆盖新版本；
- 全量重建写入新的物理索引，完成后原子切换稳定别名；
- 故障时 MySQL 商品写继续提交，搜索接口返回明确的
  `MYSQL_FALLBACK / degraded=true`；
- `m8-search` 是按需 Profile，不进入七个核心中间件常驻基线。

## 6. MinIO 对象归属

| Bucket | 所有者 | 访问规则 |
| --- | --- | --- |
| `product-media` | catalog | 对外展示通过签名 URL 或受控 CDN |
| `user-avatars` | identity | 上传者和后台审核可写 |
| `chat-attachments` | chat | 私有 Bucket；新对象进入 `quarantine/chat/{environment}/...`，扫描通过后才能绑定；下载前重新校验会话成员和对象完整性 |
| `review-media` | catalog | 规划项，当前 M8.10 尚未创建；实现后必须按内容权限读取 |
| `logistics-proofs` | fulfillment | 顾客仅可查看自己的订单证明 |
| `after-sale-evidence` | trade/售后子域 | 顾客与有权限员工可访问 |

数据库只保存对象键、大小、哈希、MIME、上传者和引用状态。任何下载都重新校验业务权限，不能仅凭对象键访问。

Chat 的过期未绑定对象由 `chat_attachment_upload` 状态机抢占清理。对象删除成功后
保留 `DELETED` 审计状态；删除失败保留 `CLEANUP_PENDING` 和错误信息，不把清理失败
解释为成功。

扫描器持续失败达到上限后保留 `SCAN_NEEDS_ATTENTION`。管理员恢复只能写入
`chat_attachment_scan_retry_audit` 并把状态重置到 `SCAN_PENDING`，不能直接制造
洁净结论。

## 7. 隐私与审计

- 密码仅保存强哈希；令牌、支付密钥和第三方凭据不进数据库明文字段。
- 手机号、邮箱和地址在普通后台列表中脱敏。
- 财务、权限、退款、库存调整和客服导出操作必须审计。
- 日志禁止输出密码、完整令牌、银行卡号和完整地址。
