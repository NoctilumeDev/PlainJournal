# M4 Payment 与结果未知恢复

> 日期：2026-07-21  
> 状态：已完成

## 1. 目标

顾客端在 Trade 订单已进入 `PENDING_PAYMENT` 后创建或读取原支付单，并完整呈现：

- 本地支付单已创建；
- 渠道处理中；
- 支付成功；
- 明确失败；
- 网络、超时、断连或非法响应导致的结果未知。

任何 HTTP 或中间件异常都不能被解释为支付成功。

## 2. 前端恢复机制

Payment Store 为每次创建保存固定命令：

```text
key       = payment:{uuid}
userId    = 当前 JWT subject
orderNo   = 当前订单号
channel   = MOCK
```

处理顺序：

1. 先按订单查询是否已有支付单；
2. 若设备有同账户待确认命令，先按原幂等键查询；
3. 仍无事实时才使用原键创建；
4. 网络、超时、非法响应或 5xx 后再次按原键查询；
5. 查询 404 时保留原命令，允许使用同一键安全重试；
6. 切换账户后不查询、不重试另一账户的待确认支付。

页面只在 Payment 返回 `SUCCESS` 后显示支付成功。`PROCESSING` 明确表示渠道最终结果仍待签名回调。

## 3. 后端契约

| Method | Path | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/payment/payments` | 使用 `Idempotency-Key` 创建或返回原支付单 |
| `GET` | `/api/v1/payment/payments/{paymentNo}` | 按支付号查询所有者事实 |
| `GET` | `/api/v1/payment/payments/by-idempotency-key/{key}` | 结果未知恢复 |
| `GET` | `/api/v1/payment/payments/by-order/{orderNo}` | 避免同订单重复创建 |
| `POST` | `/api/v1/payment/callbacks/mock` | 签名渠道回调 |

查询与创建都校验 Trade 支付上下文和 JWT 所有者。跨账户统一返回 404，避免泄露支付号或订单事实。

## 4. 真实响应丢失证据

执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
./verify-m4-payment-recovery.ps1
```

脚本默认在本机 `18601` 自行启动一次性故障代理，结束时精确停止并验证无端口泄漏；需要浏览器或外部代理时仍可显式传入 `-GatewayBaseUrl`、`-ProxyPort`、`-ArmFile` 和 `-ProxyEvidenceFile`。代理在 Payment 已返回 HTTP 200 后主动断开客户端连接。最终证据：

| 指标 | 结果 |
| --- | --- |
| 上游状态 | HTTP 200 |
| 恢复方式 | 原 `payment:{uuid}` 查询 |
| 支付单数量 | 1 |
| 支付初态 | `PROCESSING` |
| 同键重试 | 返回同一 `paymentNo` |
| 跨账户创建/查询 | 404 |
| 签名回调 | `SUCCESS` |
| 回调日志/流水/Outbox | 各 1 |
| Trade 最终状态 | `PAID` |

最终浏览器证据：

```text
paymentNo = PAY2079407153538392065
upstreamStatus = 200
before = PAY2079407153538392065|PROCESSING|378.00|1
after = 1|1|1|1|SUCCESS
tradeStatus = PAID
```

业务 ID 全程保持字符串，没有经过 JavaScript 安全整数转换。

## 5. 自动化覆盖

- foundation API 测试覆盖稳定支付键、编码后的支付号/订单号和退款补偿命令 ID；
- Payment Store 覆盖已有支付读取、单一 `PROCESSING` 创建、响应丢失恢复、查询仍未知、跨账户隔离和只查询不重复创建；
- 订单详情组件覆盖 `PROCESSING` 不显示支付成功；
- 后端 Payment 集成测试覆盖所有者隔离、幂等键、签名、回调重放、资金事实和大整数用户 ID。

## 6. 边界

当前 `MOCK` 渠道用于验证签名、派发、回调、失败和恢复机制，不代表真实支付机构接入。接真实渠道时必须保留：

- 渠道请求状态与本地支付状态分离；
- 签名、时间窗和金额校验；
- 渠道查询或回调作为最终事实；
- 不在前端保存支付敏感数据；
- 结果未知时只查询或用原键重试。
