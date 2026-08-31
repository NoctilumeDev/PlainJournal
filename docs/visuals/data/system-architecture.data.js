export const systemArchitecture = Object.freeze({
  title: "系统架构图",
  eyebrow: "运行单元、事实边界与协作方式",
  summary:
    "从使用者和两端应用进入统一网关，再落到十个事实所有者；同步短链负责即时裁决，RocketMQ 负责提交后的异步收敛。",
  actors: [
    { id: "customer", title: "顾客", detail: "浏览、交易、评价、售后与客服" },
    { id: "staff", title: "内部员工", detail: "运营、仓库、客服、财务与治理" },
  ],
  experiences: [
    { id: "storefront-web", title: "顾客端", subtitle: "storefront-web" },
    { id: "admin-web", title: "管理端", subtitle: "admin-web / 客服工作台" },
  ],
  gateway: {
    id: "ecommerce-gateway",
    title: "统一网关",
    subtitle: "Spring Cloud Gateway",
    detail: "路由 · 鉴权前置 · 限流 · 追踪",
  },
  serviceGroups: [
    {
      id: "access",
      label: "身份与目录",
      services: [
        { id: "identity-service", title: "Identity", owner: "ecom_identity", detail: "账号 · 地址 · RBAC · 风控" },
        { id: "catalog-service", title: "Catalog", owner: "ecom_catalog", detail: "商品 · 价格 · 评价 · 搜索推进" },
      ],
    },
    {
      id: "transaction",
      label: "交易核心",
      services: [
        { id: "inventory-service", title: "Inventory", owner: "ecom_inventory", detail: "现货 · 预占 · 扣减 · 流水" },
        { id: "trade-service", title: "Trade", owner: "ecom_trade", detail: "购物袋 · 结算 · 订单 · 售后" },
        { id: "marketing-service", title: "Marketing", owner: "ecom_marketing", detail: "规则 · 权益 · 定价锁 · 秒杀" },
      ],
    },
    {
      id: "delivery",
      label: "成交兑现",
      services: [
        { id: "payment-service", title: "Payment", owner: "ecom_payment", detail: "支付 · 回调 · 退款 · 对账" },
        { id: "fulfillment-service", title: "Fulfillment", owner: "ecom_fulfillment", detail: "履约 · 运单 · 轨迹 · 退货" },
      ],
    },
    {
      id: "support",
      label: "协作与触达",
      services: [
        { id: "chat-service", title: "Chat", owner: "ecom_chat", detail: "会话 · 消息 · 回执 · 附件" },
        { id: "notification-service", title: "Notification", owner: "ecom_notification", detail: "站内信 · 邮件 · 投递恢复" },
        { id: "analytics-service", title: "Analytics", owner: "ecom_analytics", detail: "来源事件 · 汇总 · 重建" },
      ],
    },
  ],
  gatewayTargets: [
    "identity-service",
    "catalog-service",
    "inventory-service",
    "trade-service",
    "marketing-service",
    "payment-service",
    "fulfillment-service",
    "chat-service",
    "notification-service",
    "analytics-service",
  ],
  synchronous: [
    { from: "Trade", to: "Catalog", label: "商品与当前价格" },
    { from: "Trade", to: "Inventory", label: "库存预占与裁决" },
    { from: "Trade", to: "Marketing", label: "权益计算与锁定" },
  ],
  eventFlow: {
    producers: ["Trade", "Inventory", "Payment", "Fulfillment", "Marketing", "Chat"],
    broker: "RocketMQ",
    consumers: ["Catalog", "Notification", "Analytics"],
  },
  infrastructure: [
    { title: "MySQL 8.4", detail: "十个独立 Schema · 最终事实" },
    { title: "Nacos", detail: "服务发现与配置" },
    { title: "Redis", detail: "缓存 · 准入 · 租约 · 路由 · GEO" },
    { title: "MinIO", detail: "商品媒体与私有附件" },
    { title: "OpenSearch", detail: "可重建商品搜索投影" },
    { title: "ClamAV", detail: "附件内容扫描边界" },
    { title: "SMTP", detail: "邮件投递通道" },
    { title: "Observability", detail: "指标 · 追踪 · 告警" },
  ],
  principles: [
    "服务只能写自己的 Schema，不跨库 JOIN。",
    "同步只处理用户必须立刻知道的裁决。",
    "事件、投影与缓存不能反写最终事实。",
  ],
});
