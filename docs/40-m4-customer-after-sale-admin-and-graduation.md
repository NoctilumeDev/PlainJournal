# M4 顾客售后、管理端与毕业报告

> 日期：2026-07-21  
> 状态：M4 已完成

## 1. 顾客逆向链路

顾客端已接通：

```text
完成订单
  -> 使用稳定 after-sale:{uuid} 申请整单退货退款
  -> 查询 Trade 审核状态
  -> 审核通过后读取 Fulfillment 退货单
  -> 提交承运商和运单号
  -> 查看仓库收货与验收
  -> 查看 Payment 退款派发、回调和最终状态
  -> Trade 售后 COMPLETED
```

售后金额和商品行可退金额读取订单不可变价格分摊快照。营销规则变化不会改写历史退款。

结果未知边界：

- 售后申请使用稳定键，失败后先查询当前账户售后；
- 待确认申请绑定用户，切换账户不重试；
- 寄回信息失败后查询退货单，不更换运单号盲重提；
- 退款 `PROCESSING/NEEDS_ATTENTION` 不显示到账；
- 退款完成只以 Payment 签名回调和 Trade 最终收敛为准。

## 2. 管理端 V1

| 工作区 | 所有者域 | 能力 |
| --- | --- | --- |
| 商品目录 | Catalog | ACTIVE 商品只读 |
| 履约与退货 | Fulfillment | 拣货、打包、发货、轨迹、异常、退货收货/验收 |
| 售后审核 | Trade | 列表、审核通过/拒绝、原因 |
| 库存 | Inventory | 仓库、库存查询、稳定流水号调整 |
| 营销 | Marketing | 规则创建、幂等权益发放 |
| 补偿与对账 | Payment + 四域 | 退款重派、追加审计、只读对账 |

路由同时执行前端角色门禁和后端 JWT/RBAC。隐藏导航不是授权手段；越权请求仍由服务端拒绝。

没有后端契约的通用管理订单/退款列表、Catalog 草稿列表和 Marketing 列表未被伪造。管理端只展示命令返回的权威事实或明确的未开放边界。

## 3. 视觉与可访问性

- 顾客端使用“素白”和“青荷”两套完整主题；
- 主题入口位于全局索引，选择持久化；
- 没有荷花背景、纹样、水波、书法或印章元素；
- 主要交互具备键盘焦点、加载、空、错误、处理中和权限不足状态；
- Playwright 对顾客权益/主题/售后和管理履约/对账页面执行 axe；
- serious / critical 可访问性违规为 0。

## 4. 最终自动化门禁

### 后端

```text
49 Surefire reports
171 tests
0 failures
0 errors
0 skipped
```

模块：

```text
platform-common 7
gateway 8
identity 7
catalog 3
inventory 23
trade 66
payment 33
fulfillment 17
marketing 7
```

独立 PMD 3.28.0 / PMD 7.17.0：11 个 Reactor 模块，0 违规。

SpotBugs 4.9.8：9 份报告、175 条 Rank 18/19 诊断，Priority 1 为 0。135 条为 Spring 注入/DTO 引用暴露，40 条为消息、回调和函数式边界显式抛异常；没有空指针、锁、竞态、资源泄漏或注入类报告。

### 前端

```text
foundation Vitest 18
storefront Vitest 49
admin Vitest 2
Playwright E2E 2
```

两端类型检查、两端生产构建、axe 和浏览器 page error 门禁均通过。
Node.js 24.14.0 / pnpm 11.9.0 已复验，`pnpm audit` 无已知漏洞。

## 5. 最终真实冒烟

完整 MySQL、Redis、Nacos、RocketMQ、MinIO 冒烟通过：

- 八应用健康、Nacos 路由、Request ID、Flyway；
- Identity、Catalog、MinIO 媒体；
- Inventory 真实竞争、预占和 Outbox；
- Trade 权威结算、幂等下单和取消；
- Payment 签名回调；
- 该 M4 历史快照为 `PaymentSucceeded -> OrderPaid -> 库存确认/履约创建`；当前
  权威顺序已收紧为 `PaymentSucceeded -> PAYMENT_CONFIRMING -> 库存确认 ->
  OrderPaid -> 履约创建`，见
  [进入 M9 前审查](69-m0-m8-pre-m9-three-layer-audit-20260728.md)；
- 正向履约、物流、签收、Trade 完成；
- 整单售后、退货验收、库存回补；
- 退款派发、授权补偿、签名退款回调；
- Trade/Payment/Inventory/Fulfillment 四域对账；
- Redis 失败锁、Gateway 限流和安全指标。

本次完整冒烟开启观测、追踪、同步韧性、Redis 故障和容量基线：Inventory 与 Trade 各执行 1000 请求/100 并发，均严格为 100 成功、900 拒绝/关闭；支付链 29.067 秒收敛，Trade Outbox 排空，未付款预占没有在收敛期间过期。Prometheus、12 条规则、四个实时目标、Alertmanager、Grafana 和 Tempo 均通过。

独立结果未知场景还证明：

- Inventory 已提交预占但响应丢失后，Trade 通过原预占号查询恢复；
- M4 Payment 创建 HTTP 200 响应丢失后，按原支付键恢复唯一支付单；
- M4 确认收货 HTTP 200 响应丢失后，查询 Fulfillment 恢复 `SIGNED` 并收敛 Trade `COMPLETED`；
- 两个 M4 专项脚本默认自建、自停一次性本地代理，最简命令不再依赖残留进程。

本次完整冒烟同时证明 `OrderPaid` 事件 DTO 污染修复、Inventory `expiresAt` 幂等契约和 M4 故障脚本收口有效。

## 6. M4 毕业判断

M4 的毕业条件已满足：

- 顾客端主交易与逆向交易均可理解、可查询、可恢复；
- 管理端只开放真实所有者域能力；
- 关键结果未知场景有故障注入证据；
- 浏览器业务 ID、金额和不可变快照边界明确；
- 自动化、Mock 浏览器和真实中间件证据相互独立；
- 文档、代码、脚本和测试基线一致；
- 未发现阻止下一阶段的未解决 P0/P1。

M4 不宣称已具备生产环境全部产品能力。真实支付渠道、承运商回调、评价/收藏、内容运营 API、生产部署与尚缺的管理查询继续后续演进。

## 7. 下一里程碑

进入 M5“容量基线与普通业务高并发优化”：

1. 固定单机资源与数据量；
2. 建立 Catalog、购物车、结算、普通下单、支付回调和订单查询压测；
3. 记录吞吐、P50/P95/P99、错误率、CPU、内存、GC、连接池、慢查询、锁等待和 MQ 积压；
4. 按证据优化 SQL、索引、线程池和连接池；
5. 为 Catalog 建立代表性的本地缓存 + Redis 多级缓存；
6. 验证穿透、击穿、雪崩、失效事件、Redis 故障、背压和资源隔离。

多商户和 Go 统计服务仍按 M9 进入条件推进，不提前污染当前自营 B2C 主干。
