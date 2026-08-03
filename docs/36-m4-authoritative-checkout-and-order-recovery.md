# M4 权威结算、幂等下单与订单恢复

> 日期：2026-07-20  
> 状态：M4 第四批已完成  
> 范围：购物车展示快照、提交前权威复核、Trade 幂等下单、响应未知恢复、订单结果页

## 1. 结论

本批把第三批的只读结算草稿推进为真实订单闭环：

```text
Trade 购物车展示快照
  -> Catalog 当前商品与价格
  -> Inventory 权威可用库存
  -> Marketing 当前资格与无副作用试算
  -> 60 秒权威快照
  -> 稳定 order:{uuid} + 固定请求
  -> Trade 创建订单
  -> 按原幂等键恢复响应未知
  -> 订单结果页
```

闭环止于 Trade 订单结果。`PENDING_PAYMENT` 只表示订单已经建立且库存已经预占；Payment 本批没有接入，前端不会把订单创建成功解释为支付成功。

## 2. 购物车展示快照

真实链路首次验证时发现，旧购物车读取会再次调用 Catalog，因此 Catalog 从 ¥189.00 改为 ¥199.00 后，购物车也静默变成 ¥199.00。这样前端无法向用户解释“购物车旧价”和“提交前当前价”的差异。

Trade 新增 Flyway V12：

```text
V12__add_cart_display_snapshots.sql
```

`cart_item` 增加：

- `product_title`
- `sku_name`
- `spec_json`
- `unit_price`

PUT 购物车和游客购物袋合并时保存展示快照。新行读取不再调用 Catalog；历史旧行快照为空时才回退查询 Catalog。展示快照只用于可解释的购物车体验，最终成交仍由提交前当前事实和 Trade 下单时的服务端读取裁决。

## 3. 提交前权威复核

Checkout Store 在允许提交前重新读取：

- Catalog 当前商品、SKU、状态和单价；
- Inventory 当前可用库存；
- Marketing 当前可用权益与无副作用试算；
- Identity 当前选中地址。

规则：

- 价格变化明确标记，并以当前价格重算；
- 库存不足阻止提交；
- Marketing 试算不写 `pricing_lock`，不改变权益状态；
- 权威快照有效期为 60 秒；
- 商品、地址、权益或购物车变化会使旧快照失效；
- Inventory 的查询结果只用于提交前提示，最终库存仍由 Trade 调用 Inventory 后的 MySQL 条件预占裁决。

## 4. 稳定幂等键与结果未知恢复

前端为一份固定订单命令生成：

```text
order:{uuid}
```

该键、账户和固定请求保存在本机待确认记录中。以下情况不会立即解释为失败：

- 网络不可达；
- 请求超时；
- 非法响应结构；
- HTTP 5xx。

前端会先调用：

```http
GET /api/v1/trade/orders/by-idempotency-key/{key}
```

恢复规则：

- 查询到订单：进入该订单结果页；
- 返回 404：保留原请求和原键，允许原键安全重试；
- 切换账户：不复用其他账户的待确认请求；
- 任何重试都不得换键或修改原请求。

Trade 在调用 Address、Catalog、Marketing 或 Inventory 前先查询稳定幂等事实。已存在且请求哈希一致时直接返回原订单，不再次调用远程依赖。请求哈希使用地址 ID、规范化并排序的商品行与权益编号等客户端命令事实，不使用重试时可能变化的远程快照。跨用户按键查询返回 404。

## 5. 订单结果页

顾客端新增：

```text
/orders/:orderNo
```

页面查询 Trade 订单并展示：

- `PENDING_STOCK`、`PENDING_PAYMENT`、`CLOSED` 等真实状态；
- 商品标题、SKU、规格、单价和商品行金额快照；
- 收件人、电话、地区、详细地址和邮编快照；
- 原价、各类优惠、应付金额、定价版本和锁价编号；
- 刷新订单状态入口。

`PENDING_PAYMENT` 的页面文案是“库存已经预占”，并明确声明 Payment 尚未开放。订单结果页没有支付成功视觉或伪造的支付入口。

## 6. 自动化门禁

前端执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\frontend
pnpm check
```

结果：

| 包 | 测试 |
| --- | ---: |
| foundation | 8 |
| storefront | 18 |
| admin | 1 |
| 合计 | 27 |

两端类型检查和生产构建同时通过。Checkout 测试覆盖实时价格、库存不足、响应丢失恢复、待确认保留、原键重试和跨账户隔离。

后端执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn clean verify
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
```

结果：

| 模块 | 测试 |
| --- | ---: |
| platform-common | 7 |
| ecommerce-gateway | 8 |
| identity-service | 7 |
| catalog-service | 3 |
| inventory-service | 19 |
| trade-service | 60 |
| payment-service | 29 |
| fulfillment-service | 12 |
| marketing-service | 7 |
| 合计 | 152 |

共 43 份 Surefire 报告，0 失败、0 错误、0 跳过。PMD Maven Plugin 3.28.0 / PMD 7.17.0 全 Reactor 通过。Trade 测试新增覆盖同键恢复不重复调用远程服务、按键查询所有者隔离，以及 Catalog 改价后购物车展示快照保持不变。

## 7. 真实中间件验证

验证使用真实：

- MySQL
- Redis
- Nacos
- RocketMQ NameServer、Broker、Proxy
- MinIO
- Gateway
- Identity
- Catalog
- Inventory
- Trade
- Marketing

Payment 与 Fulfillment 没有启动。Trade 启动日志确认真实 MySQL schema 从 11 升级到 12，V12 成功应用。

执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\verify-m4-authoritative-checkout.ps1
```

最终证据：

| 事实 | 结果 |
| --- | --- |
| 购物车展示单价 | ¥189.00 |
| Catalog 当前单价 | ¥199.00 |
| Inventory 下单前可用库存 | 5 |
| Marketing 原价 / 优惠 / 应付 | ¥398.00 / ¥20.00 / ¥378.00 |
| 稳定键 | `order:{token}` |
| 订单状态 | `PENDING_PAYMENT` |
| Trade 事实 | 1 笔订单、1 个订单号、1 个待支付状态 |
| Inventory 事实 | 1 笔预占、状态 `RESERVED` |
| Marketing 事实 | 1 笔价格锁、权益 `LOCKED` |
| 响应恢复 | 按键查询、同键重试、订单号查询均返回同一订单 |
| JSON ID | User、Product、SKU、Cart、Inventory、Trade 均保持 string |
| 取消恢复 | available 5、reserved 0、权益 `AVAILABLE` |

营销权益释放由 `OrderCanceled` 经 Outbox/RocketMQ 异步推进。验证脚本使用有界轮询等待最终状态，不把取消 HTTP 返回时的瞬时 `LOCKED` 误判为业务失败。

脚本在 `finally` 中精确删除五域临时事实和 Redis 临时键；首轮验证后再次查询，临时用户、商品、分类、品牌、仓库、订单和营销规则均为 0。浏览器留存轮同样以退出码 0 完成取消和清理；清理失败会使脚本失败。

## 8. 真实浏览器验收

使用同一真实 Gateway 和服务，通过：

```powershell
.\verify-m4-authoritative-checkout.ps1 -BrowserHoldSeconds 180
```

在自动取消和清理前保留有限验收窗口。浏览器确认：

一次性账号密码和订单请求键只写入 `.run` 下的短生命周期 fixture 文件；控制台只输出
文件路径、邮箱、订单号和留存时长的脱敏摘要，`finally` 会精确删除 fixture，避免
人工浏览器证据日志保存密码。

- 登录后账户购物袋显示 2 件商品；
- 权威核对显示实时单价 ¥199.00、可用 3 件和“价格已变化”；
- 金额从购物车展示 ¥378.00 更新为当前原价 ¥398.00；
- 订单结果页显示 `PENDING_PAYMENT` 与“库存已经预占”；
- 商品、地址与价格不可变快照完整；
- 页面明确写明“订单成功不等于支付成功”；
- console 只有 Vite 连接日志，没有 error。

该验收直接连接真实服务，不是 Mock API；仍属于人工浏览器验收，不等价于已落地自动 E2E。

收口时已精确停止本批启动的六个 Java 应用和 Vite，使用 Compose `stop` 停止七个 core 容器而不删除卷，并关闭本批启动的 Docker Desktop。最终没有 PlainJournal Java 进程或项目端口监听，Docker Engine 恢复为不可用状态。

## 9. 下一批边界

下一批先关闭 Trade 顾客订单中心，再进入 Payment：

1. 建立订单列表。
2. 接通顾客取消入口，呈现 `CANCELING/CANCELED` 和取消结果未知查询恢复。
3. 完整解释 `PENDING_STOCK/PENDING_PAYMENT/CLOSED` 等状态。
4. 上述边界稳定后接通 Payment 创建、渠道派发处理中和结果未知查询。
5. 随 Payment/Fulfillment 页面继续复核浏览器业务 ID，并补自动 E2E、可访问性和移动宽度门禁。

Payment 接入仍必须遵守：中间件异常不能伪造成功，允许“处理中/结果未知”，并通过查询、重试、补偿和对账收敛。
