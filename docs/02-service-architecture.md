# 服务架构

## 1. 总体结构

```mermaid
flowchart TB
    Customer["顾客商城"] --> Gateway["ecommerce-gateway"]
    Admin["管理后台 / 客服工作台"] --> Gateway

    Gateway --> Identity["identity-service"]
    Gateway --> Catalog["catalog-service"]
    Gateway --> Trade["trade-service"]
    Gateway --> Payment["payment-service"]
    Gateway --> Fulfillment["fulfillment-service"]
    Gateway --> Marketing["marketing-service"]
    Gateway --> Chat["chat-service"]
    Gateway --> Notification["notification-service"]
    Gateway --> Analytics["analytics-service"]

    Trade --> Catalog
    Trade --> Inventory["inventory-service"]
    Trade --> Marketing

    Identity --- MySQL[("MySQL: 独立 schema")]
    Catalog --- MySQL
    Catalog --- OpenSearch["OpenSearch（可重建搜索投影）"]
    Inventory --- MySQL
    Trade --- MySQL
    Payment --- MySQL
    Fulfillment --- MySQL
    Marketing --- MySQL
    Chat --- MySQL
    Notification --- MySQL
    Analytics --- MySQL

    Gateway --- Nacos["Nacos"]
    Identity --- Nacos
    Notification --- Nacos
    Trade --- Redis["Redis"]
    Inventory --- Redis
    Fulfillment --- Redis
    Chat --- Redis
    Catalog --- MinIO["MinIO"]
    Chat --- MinIO

    Trade --> MQ["RocketMQ"]
    Inventory --> MQ
    Payment --> MQ
    Fulfillment --> MQ
    Marketing --> MQ
    Chat --> MQ
    MQ --> Catalog
    MQ --> Notification
    MQ --> Analytics
    Notification --> SMTP["SMTP provider"]
```

## 2. 服务职责

| 服务 | 默认端口 | 单一职责 | 不负责 |
| --- | ---: | --- | --- |
| `ecommerce-gateway` | `18000` | 路由、鉴权前置、限流、请求追踪 | 业务规则、数据库访问 |
| `identity-service` | `18101` | 顾客与员工账号、地址、RBAC、令牌和登录风控 | 订单、客服消息 |
| `catalog-service` | `18102` | 类目、品牌、SPU、SKU、价格、上下架、商品媒体、商品评价和可重建搜索投影 | 库存数量、订单价格快照；把 OpenSearch 当最终事实 |
| `inventory-service` | `18103` | 仓库、现货、预占、释放、确认扣减和库存流水 | 商品营销价格、订单状态 |
| `trade-service` | `18104` | 购物车、结算、订单、订单快照、取消、收货和售后申请 | 支付渠道流水、物理库存直接更新 |
| `payment-service` | `18105` | 支付单、渠道请求、回调、退款和对账 | 擅自修改订单、库存 |
| `fulfillment-service` | `18106` | 拣货、包裹、发货、运单、追加式物流轨迹、MySQL 空间位置投影、Redis GEO 加速和退货收货 | 支付结果判定、用 Redis 裁决物流事实 |
| `marketing-service` | `18107` | 优惠券、活动、优惠计算和秒杀资格 | 最终支付金额持久化、最终库存 |
| `chat-service` | `18108` | 会话、可靠消息、客户端幂等、多节点实时路由、离线回放、附件安全和客服认领 | 直接查询订单或用户数据库；未扫描就放行附件 |
| `notification-service` | `18109` | 站内信、邮件偏好、可靠邮件投递、消费失败和审计恢复 | 决定订单状态；把 SMTP 接受解释为交易成功 |
| `analytics-service` | `18110` | Trade/Payment 事件日志、日/商品汇总、对账、审计重建和运营总览 | 跨服务 JOIN；修改订单、支付或履约事实；估算缺失收入 |

管理后台是客户端，不再单独创建一个包含全部业务的 `admin-service`。后台请求通过网关进入对应领域服务。

## 3. 同步与异步边界

同步调用只用于用户必须立即知道结果的短链路：

- 订单结算时查询 SKU 当前状态和价格。
- 创建订单时请求库存预占。
- 使用优惠券时请求营销服务计算并核销资格。
- 客服绑定订单时校验顾客是否有权查看该订单。
- 顾客查询或提交评价只读取 Catalog 的本地评价资格投影，不在请求链上同步调用
  Trade。

异步事件用于跨服务状态推进：

- 支付成功、支付失败和退款结果。
- 订单取消、超时关闭和确认收货。
- 库存预占、释放、确认扣减和低库存告警。
- 发货、物流节点和签收。
- Trade 发布带不可变订单行快照的 `OrderCompleted`，Catalog 幂等生成评价资格。
- Catalog 商品事务只提交 MySQL 事实和同库搜索 Outbox；后台任务再把当前权威状态
  投影到 OpenSearch。搜索结果回读 MySQL，索引不可用时明确退化为基础匹配。
- Trade/Payment 已发生事实由 Analytics 异步消费并写入自己的来源事件日志和汇总；
  管理端不在请求链上同步聚合多个服务。
- 聊天持久化事件、跨节点定向投递与各类通知。

禁止形成同步调用环。例如 `trade -> payment -> trade` 必须改为 `trade -> payment` 创建支付单，支付结果通过事件返回。

## 4. 分阶段启动

考虑本机 16 GB 内存，应用服务按配置组运行：

| 配置组 | 服务 |
| --- | --- |
| `foundation` | gateway、identity、catalog、inventory、trade |
| `transaction` | payment、fulfillment |
| `collaboration` | chat、notification |
| `operations` | analytics（按需启动，消费事件并提供管理端运营读模型） |
| `campaign` | marketing、秒杀相关压测组件 |

开发某条链路时只启动有关服务。代表性全链路验收也必须遵守资源预算，不要求所有服务
同时扩为多实例。

## 5. 当前代码目录

```text
PlainJournal/
├── backend/
│   ├── platform-common/          # 仅技术能力，不放业务 Entity
│   ├── ecommerce-gateway/
│   └── services/
│       ├── identity-service/
│       ├── catalog-service/
│       ├── inventory-service/
│       ├── trade-service/
│       ├── payment-service/
│       ├── fulfillment-service/
│       ├── marketing-service/
│       ├── chat-service/
│       ├── notification-service/
│       └── analytics-service/
├── frontend/
│   ├── storefront-web/
│   └── admin-web/
├── deploy/
└── docs/
```

每个服务内部采用 `interfaces -> application -> domain -> infrastructure` 的轻量 DDD 分层。简单 CRUD 可以由 application service 直接协调仓储，不为形式制造大量空接口。

## 6. 关键约束

- 服务只能写自己的 schema，不能跨库 JOIN 或直接调用别人的 Mapper。
- 对外契约使用请求/响应 DTO 和事件 DTO，禁止共享数据库 Entity。
- `platform-common` 只允许放统一异常、日志追踪、鉴权上下文、序列化和测试基础设施。
- 任何跨服务重试都必须以幂等键为前提。
- 后台“超级管理员”可以发起授权操作，但不能绕过领域状态机直接改表。
