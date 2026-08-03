# M4 履约与物流时间线

> 日期：2026-07-21  
> 状态：已完成

## 1. 范围

顾客订单详情已接通 Fulfillment 所有者域：

- 履约单创建与当前状态；
- 拣货、打包、发货；
- 追加式物流轨迹；
- 顾客确认收货；
- 确认响应未知后的权威查询恢复；
- Trade 订单最终完成。

管理端按 `ADMIN/WAREHOUSE` 角色执行履约和退货命令，不允许顾客 JWT 操作仓库接口。

## 2. 状态真实性

```text
CREATED -> PICKING -> PACKED -> SHIPPED -> IN_TRANSIT -> DELIVERING -> SIGNED
              \-> EXCEPTION                 \-> EXCEPTION
```

顾客端不根据按钮点击推测状态。每次操作都等待 Fulfillment 返回权威响应；网络、超时、非法响应或 5xx 后查询同一订单：

- 查询为 `SIGNED`：确认收货已完成；
- 查询仍为 `SHIPPED/IN_TRANSIT/DELIVERING`：结果未知，允许安全重试同一路径；
- 其他状态：显示真实状态，不伪造签收。

Trade 的 `COMPLETED` 由 `ShipmentSigned` 事件最终推进，因此 Fulfillment `SIGNED` 和 Trade `COMPLETED` 在短时间内允许不同步。

## 3. 时间线

Fulfillment View 同时返回：

- 当前履约状态；
- 不可变配送地址快照；
- 承运商和运单号；
- 状态历史；
- 追加式物流轨迹。

轨迹以外部事件 ID 幂等。相同事件号同内容重复不追加；同事件号异内容返回冲突。已发生历史不被覆盖。

## 4. 真实响应丢失证据

执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
./verify-m4-fulfillment-timeline.ps1
```

脚本默认在本机 `18602` 自行启动一次性故障代理，结束时精确停止并验证无端口泄漏；需要浏览器或外部代理时仍可显式传入 `-GatewayBaseUrl`、`-ProxyPort`、`-ArmFile`、`-ProxyEvidenceFile` 和 `-BrowserFixtureFile`。一次性密码只进入短生命周期 fixture，控制台输出脱敏摘要，`finally` 精确删除文件。代理在确认收货上游已返回 HTTP 200 后断开响应。最终证据：

| 指标 | 结果 |
| --- | --- |
| 配置来源 | Nacos |
| 初始履约状态 | `CREATED` |
| 确认前状态 | `DELIVERING` |
| 上游确认状态 | HTTP 200 |
| 查询恢复状态 | `SIGNED` |
| Trade 最终状态 | `COMPLETED` |
| 状态历史 | 7 条 |
| 物流轨迹 | 3 条 |
| `ShipmentSigned` Outbox | 1 条 |
| 跨账户查询 | 404 |
| 浏览器业务 ID | string |

数据库事实汇总：

```text
1 fulfillment_order
7 fulfillment_status_history
3 logistics_trace
1 ShipmentSigned outbox
status = SIGNED
```

Trade 只产生一条 `COMPLETED` 状态历史。

## 5. OrderPaid 事件契约修复

最终完整冒烟发现 Trade 曾把浏览器 `AddressSnapshotView` 放入 `OrderPaid.deliveryAddress`。浏览器 DTO 为防止 JavaScript 精度丢失，把 `sourceAddressId` 序列化为 string；Fulfillment 旧消费者只接受 JSON number，履约单因此无法创建。

修复后：

- Trade 用独立 Map 构造内部事件地址，保持 `sourceAddressId` 为 JSON integer；
- Fulfillment 同时兼容正整数 number 与十进制正整数字符串；
- 解析坏载荷进入 `consumer_failure.NEEDS_ATTENTION` 并 ACK；
- 业务失败有限重试，成功后标记 `RECOVERED`；
- 测试直接断言 Outbox 的地址 ID 数值语义和历史字符串兼容。

该修复明确了长期边界：浏览器响应 DTO 与内部版本化事件 DTO 不能复用。

## 6. 自动化覆盖

- foundation Fulfillment API 路径与顾客/仓库命令隔离；
- Store 覆盖 404 未物化、确认响应丢失恢复和持续未知；
- 订单详情组件覆盖追加式物流和 `SIGNED` 后确认；
- Fulfillment 集成测试覆盖所有者隔离、大整数 ID、状态机、轨迹、退货；
- `OrderPaidConsumerTest` 覆盖坏载荷、有限重试和历史字符串 ID。
