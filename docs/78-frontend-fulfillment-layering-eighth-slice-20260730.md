# 前端低耦合分层第八批：Fulfillment 积木与真实签收恢复

> 日期：2026-07-30  
> 状态：本批完成，M9 仍冻结  
> 边界：不改变 Fulfillment/Trade 状态机、消息语义或所有者数据库；顾客端只组合并展示权威事实

## 1. 本批目标

订单详情中的履约代码原先同时持有会话、Fulfillment 查询、物流位置、确认收货、
结果未知恢复和大段页面标记。它虽然能工作，但存在三个风险：

1. 账户或令牌切换后，旧请求可能覆盖当前页面；
2. 同一确认动作可以在程序层并发触发；
3. 视图必须同时理解网络不确定性和 Fulfillment 状态机。

本批把这部分拆成两个公开积木：

```text
entities/fulfillment
  权威履约/位置事实、响应身份校验、owner/token/revision 隔离

features/order-fulfillment
  确认收货意图、同请求合并、结果未知查询恢复、履约界面

views/OrderDetailView.vue
  只传入显式访问上下文和 Trade 订单，并接收签收完成事件
```

售后和评价仍位于旧迁移缝，本批没有为了目录对称强行搬动无关代码。

## 2. 高风险边界

### 2.1 所有者与迟到响应

Fulfillment entity 不读取实时 session。调用者必须显式传入：

```text
authenticated + ownerId + accessToken
```

每次 owner/token 变化都会推进访问代次。旧账户或旧令牌响应即使晚到，也不能写入
当前事实。返回的 `orderNo`、`userId` 和位置所属 `fulfillmentNo` 还要与请求及
已知权威事实一致；不一致响应不会落入页面状态。

### 2.2 并发确认与结果未知

同一账户、同一订单、同一访问代次的并发确认意图共享一个活动命令，因此只产生
一次 POST。只有 `SHIPPED/IN_TRANSIT/DELIVERING` 可以发出确认命令。

网络、超时、非法响应、5xx 或响应身份不匹配不能解释为成功。页面随后查询
Fulfillment 所有者事实：

- 查询为 `SIGNED`：恢复成功；
- 查询仍非 `SIGNED` 或无法确认：只显示“结果待确认”；
- Trade `COMPLETED`：继续等待 `ShipmentSigned` 消息收敛后重新查询。

本批测试还发现，使用 `void promise.finally(cleanup)` 会产生一个新的拒绝 Promise；
账户切换时主调用虽正确拒绝，派生 Promise 却可能成为浏览器未处理异常。现改为
`then(cleanup, cleanup)`，既清理活动状态，也不吞掉原调用的账户切换错误。

## 3. 商品图片与青荷视觉

本批使用内置图像生成能力制作两张 4:5 商品摄影图：

- `public/images/catalog/canvas-commuter-tote.png`
- `public/images/catalog/mist-blue-notebook.png`

受控浏览器夹具通过正常 Catalog `coverUrl/media.url` 返回它们，组件不根据商品标题
猜图。生产链仍以 Catalog/MinIO 返回的媒体 URL 为准；本地图片不是生产商品事实。

履约区另保留一张低调的“青荷包裹路线”视觉：

- `src/assets/fulfillment/qinghe-parcel-route.png`

它只解释“每一步都有事实落点”，不替代轨迹、位置或状态。真实浏览器检查发现
`min-height: 12rem` 与 `aspect-ratio: 16/6` 会把 488px 主栏反向撑到 512px；
现由父积木宽度决定图片尺寸，桌面为 488/488px，390×844 视口为约
354.7/354.7px，均无页面横向溢出。

## 4. 三层证据

| 结论 | 代码证明 | 自动化测试 | 真实运行证据 |
| --- | --- | --- | --- |
| 账户/令牌迟到响应不能串户 | `fulfillmentStore.ts` 的 owner/token/access revision 与返回身份校验 | Entity 测试覆盖 A→B、同 owner 换 token、订单/用户/履约号不匹配 | 第二轮浏览器只展示当前短期账户的订单号和履约号；跨账户 404 由真实脚本复核 |
| 同一确认不会并发重复提交 | `receiptConfirmationStore.ts` 合并同订单活动 Promise | 并发调用只观察到一次 POST；非可确认状态零 POST | 真实代理只捕获一次目标 POST |
| 响应丢失不伪造成功 | 不确定失败后查询 Fulfillment，仅 `SIGNED` 清除未知状态 | 覆盖丢响应后 `SIGNED`、查询仍 `SHIPPED` 两个分支 | 上游 200 后代理丢弃 2863 字节响应，页面查询恢复 `SIGNED`，随后 Trade 为 `COMPLETED` |
| 中文不可变快照完整 | 验证脚本 MySQL 客户端显式 `utf8mb4` | 脚本断言 Trade SKU、Trade 地址和 Fulfillment 地址中文值 | 浏览器显示“默认规格”和“浙江省 杭州市 西湖区 文三路 1 号” |
| 运行后不污染下一轮 | 清理先删 `shipment_latest_position`，再删轨迹与履约单 | 脚本成功走完 finally；PowerShell AST 为 0 错误 | 五个所有者域 run-scoped 计数全 0；项目端口、JVM、Vite 全 0 |
| 图片真实加载且不越界 | Catalog 媒体 URL、Fulfillment scoped CSS | ProductCard、OrderDetail 与完整构建/E2E 覆盖 | 商品图实际解码 1122×1402；履约图 1672×941；桌面和移动端无横向溢出、控制台零 warning/error |

真实证据：

```text
backend/.run/frontend-order-fulfillment-eighth-20260730-r2/
  fulfillment-confirm-proxy-evidence.json
  fulfillment-verification.out.log
  workspace-verification.json
```

领域事实证据：

```text
backend/.run/m4-fulfillment-timeline-20260721/http-evidence.json
```

第二轮关键结果：

```text
upstream confirmation HTTP     200
dropped response bytes         2863
Fulfillment                    DELIVERING -> SIGNED
Trade                          SHIPPED -> COMPLETED
history / traces               7 / 3
related consumer failures      0
cross-account lookup           404
browser console warn/error     0 / 0
desktop horizontal overflow    0
mobile horizontal overflow     0
run-scoped database residue    0
project ports/JVM/Vite         0 / 0 / 0
```

## 5. 最终前端门禁

修复后的串行 `pnpm check` 基线：

```text
Foundation Vitest             42
Storefront Vitest             94
Admin Vitest                  12
Vitest total                 148
Playwright Mock E2E           14
layer rules                   13
layered files / imports       68 / 188
typecheck / build / axe       PASS
```

Mock E2E 继续只是受控浏览器契约门禁。资金、库存、权益、权限和本批确认收货结论均
不能只依赖 Mock；本批 Fulfillment 已补齐代码、自动化测试和真实中间件/浏览器三层。

## 6. 下一步边界

Fulfillment 前端积木已经完成，但售后、退款、评价和管理端履约仍有各自迁移缝。
后续可以继续按同一方式逐块收敛，不应把所有领域塞进一个“万能订单 feature”。

本批不构成 M9 准入。三个商户和 Go 异构服务继续冻结，等待用户完成前端与
M0–M8 复审后单独确认。
