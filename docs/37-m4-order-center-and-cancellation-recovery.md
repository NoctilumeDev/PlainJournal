# M4 订单中心与取消结果未知恢复

> 日期：2026-07-21  
> 状态：M4 第五批已完成  
> 范围：顾客订单列表、订单详情、顾客取消、所有者隔离、取消响应丢失查询恢复

## 1. 结论

本批把第四批的单笔订单结果页扩展为可持续使用的顾客订单中心：

```text
当前账户订单列表
  -> 订单详情与不可变快照
  -> 二次确认取消
  -> Trade 返回 CANCELED / CANCELING
  -> 响应未知时立即查询同一订单
  -> PENDING_PAYMENT 保留同账户待确认记录
  -> 使用同一取消路径安全重试
  -> Trade 最终收敛并刷新真实状态
```

前端只在 Trade 返回 `CANCELED` 后显示“订单已取消”。`CANCELING` 明确表示库存和营销权益仍在释放；网络、超时、非法响应或 5xx 不会被解释为取消成功。

## 2. 顾客订单中心

Foundation Trade API 新增：

```http
GET  /api/v1/trade/orders
POST /api/v1/trade/orders/{orderNo}/cancel
```

顾客端新增：

- `/orders`：按创建时间倒序展示当前账户订单；
- `/orders/:orderNo`：展示状态、商品、地址和价格不可变快照；
- 账户页“我的订单”入口；
- 统一订单状态文案，覆盖 `PENDING_STOCK`、`PENDING_PAYMENT`、`CANCELING`、`CANCELED`、`CLOSED`、支付与履约状态；
- 仅 `PENDING_PAYMENT` 显示顾客取消入口，并要求二次确认；
- 非可取消状态不发送取消 POST。

Payment 本批仍未接入。`PENDING_PAYMENT` 继续只表示订单已建立且库存已预占，不表示支付成功。

## 3. 取消结果未知恢复

Pinia `orders` Store 为取消命令保存：

- 当前账户 `userId`；
- `orderNo`；
- 待确认记录创建时间。

恢复规则：

1. 发起取消前先确认 Trade 当前状态为 `PENDING_PAYMENT`。
2. POST 返回 `CANCELING` 时展示“订单正在取消”，不提前显示终态。
3. POST 返回 `CANCELED` 时才清除待确认记录并展示取消完成。
4. 网络、超时、非法响应或 HTTP 5xx 后立即 GET 同一订单。
5. GET 返回 `CANCELING/CANCELED` 时以 Trade 事实恢复。
6. GET 仍返回 `PENDING_PAYMENT` 时保留同账户待确认记录，允许查询后使用同一路径安全重试。
7. 切换账户后不查询、不重试其他账户的待确认取消。
8. 业务 4xx 等确定失败会清理本次待确认记录，不伪装成结果未知。

取消命令不需要额外客户端幂等键。服务端以订单状态机、锁和稳定库存预占号实现重复调用幂等；前端始终重试同一订单的同一路径。

## 4. 所有者隔离

Trade 的订单列表按 JWT subject 过滤。订单详情、幂等键查询和顾客取消均校验订单所有者；跨账户访问统一返回：

```text
404 RESOURCE_NOT_FOUND
```

该语义避免通过 403 暴露订单号是否存在。真实验证确认：

- 订单所有者列表只返回自己的订单；
- 另一账户订单列表为空；
- 另一账户读取和取消该订单均返回 404；
- 越权取消没有改变原订单的 `PENDING_PAYMENT` 状态。

## 5. 自动化门禁

前端在 Node.js 24.14.0、pnpm 11.9.0 下执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\frontend
pnpm check
```

结果：

| 包 | 测试 |
| --- | ---: |
| foundation | 9 |
| storefront | 27 |
| admin | 1 |
| 合计 | 37 |

两端类型检查和生产构建同时通过。订单 Store 测试覆盖：

- 当前账户订单列表；
- `CANCELING/CANCELED` 状态真实性；
- 取消响应丢失后的 GET 恢复；
- Trade 仍返回 `PENDING_PAYMENT` 时保留待确认记录；
- 查询后使用同一取消路径安全重试；
- 跨账户不查询、不重试；
- 非可取消状态不发送 POST。

后端执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn clean verify
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
```

结果：

| 指标 | 结果 |
| --- | ---: |
| Surefire 报告 | 43 |
| 后端测试 | 154 |
| Trade 测试 | 62 |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 |
| PMD | 全 Reactor 0 违规 |

Trade 新增契约覆盖当前账户列表、另一账户空列表、跨账户取消 404，以及 Inventory 释放结果未知时重复取消仍保持唯一 `CANCELING` 迁移。

两个新增 PowerShell 验证工具均通过 PowerShell Parser 校验：

- `backend/tools/trade-cancel-response-drop-proxy.ps1`
- `backend/tools/storefront-verification-server.ps1`

## 6. 真实故障验证

验证使用真实：

- MySQL、Redis、Nacos、RocketMQ、MinIO；
- Gateway、Identity、Catalog、Inventory、Trade、Marketing；
- 构建后的 Storefront；
- 一次性取消响应丢失代理。

Payment、Fulfillment 和观测栈没有启动，也没有被描述成本批证据。

真实故障流程：

1. 建立一笔 `PENDING_PAYMENT` 订单，金额为 ¥398.00 - ¥20.00 = ¥378.00。
2. 当前账户订单列表返回该订单，另一账户列表为空。
3. 停止 Inventory，发起顾客取消。
4. Trade 已完成请求并向代理返回 HTTP 200；代理记录响应摘要后主动断开浏览器响应。
5. 前端立即 GET Trade，恢复为 `CANCELING`，显示“订单正在取消”和“完成前不会提前显示取消成功”。
6. 恢复 Inventory 后，Trade 恢复任务使用原预占号推进到 `CANCELED`。
7. Inventory 收敛为 available 5 / reserved 0。
8. Marketing 权益经 `OrderCanceled` 收敛为 `AVAILABLE`。
9. 浏览器刷新后显示“订单已取消”，console 无 error。

响应丢失证据记录在：

```text
backend/.run/m4-order-center-20260721/trade-cancel-proxy-evidence.json
```

证据包含 `upstreamStatus = 200`、响应字节数与 SHA-256，不保存响应正文。主验证脚本退出成功后，逐库复核临时账号、登录记录、订单、Outbox、商品、分类、品牌、库存余额、预占、价格锁和营销规则均为 0。

## 7. 浏览器验收

真实浏览器确认：

- 账户页存在“我的订单”入口；
- 订单列表只展示当前账户事实；
- 订单详情完整展示金额、商品和地址快照；
- 取消操作有二次确认；
- Inventory 故障与响应丢失期间只显示 `CANCELING`，不显示成功；
- Inventory 恢复后刷新得到 `CANCELED`；
- Payment 未开放边界仍然明确；
- 浏览器 console 无 error。

验证站点使用构建产物并提供 History API fallback，同时把 `/api/**` 转发到指定验证上游。Vite 开发服务器也支持通过 `VITE_API_PROXY_TARGET` 显式选择 API 代理目标。

## 8. 下一批边界

下一批进入 Payment 最小闭环：

1. 创建或读取当前订单的支付单，不重复创建渠道事实。
2. 明确区分本地支付单已创建、渠道派发处理中、支付成功、失败与结果未知。
3. 渠道请求超时或响应丢失后先查询 Payment 权威事实，不伪造成功，也不盲目创建第二笔支付。
4. 页面刷新、重新登录和同账户重试后仍能恢复原支付单。
5. 逐 DTO 复核 Payment 浏览器业务 ID 字符串契约。
6. 使用真实 MySQL、Nacos、Gateway、Trade、Payment 与可控渠道故障完成浏览器和 HTTP 验证；需要 MQ 推进时再加入真实 RocketMQ。

履约、售后和完整管理工作区仍保持后续批次边界。
