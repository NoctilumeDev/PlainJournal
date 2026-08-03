# M4 地址管理与只读结算试算

> 日期：2026-07-20  
> 状态：M4 第三批已完成  
> 范围：地址管理、账户购物车读取、只读结算草稿、Marketing 无副作用试算

## 1. 结论

本批完成了从“已登录账户”进入结算前核对的最小闭环：

```text
会话恢复
  -> 地址列表 / 新增 / 编辑 / 默认地址 / 删除
  -> Trade 账户购物车
  -> Marketing 可用权益
  -> 无副作用价格试算
```

该闭环只读取和试算，不创建订单、不写营销价格锁、不改变权益状态、不预占库存。页面没有订单提交按钮，因此不会以视觉成功覆盖尚未接通的交易事实。

## 2. 前端交付

### 2.1 地址管理

顾客端新增 `/account/addresses`：

- 读取当前用户全部地址；
- 新增地址，第一个地址仍由 Identity 自动设为默认；
- 编辑地址，进入编辑状态后焦点移动到表单标题；
- 切换默认地址，以服务端返回事实刷新列表；
- 删除使用原位确认，可取消；只有服务端确认成功后才移除；
- 成功状态使用 `role="status"`，失败使用 `role="alert"`；
- 表单和错误在失败时保留，支持修正或重试；
- 地址 ID 全程使用 `BusinessId = string`。

### 2.2 账户购物车

登录后的 `/bag` 读取 `GET /api/v1/trade/cart/items`：

- 设备游客购物袋与账户购物车是两类事实；
- 合并成功不等于随后读取一定成功，两类结果分别呈现；
- 账户购物车金额使用十进制字符串和分值运算，不经过浮点累计；
- 当前仍不锁库存，也不代表最终成交价格。

### 2.3 只读结算草稿

顾客端新增 `/checkout`，并行读取：

- Identity 地址；
- Trade 账户购物车；
- Marketing 当前用户权益。

选择地址和权益后调用：

```http
POST /api/v1/marketing/pricing-previews
```

请求包含配送地区代码、商品行 SKU、逐行金额与选择的权益编号。前端规则为：

- 默认选择后端默认地址；
- 每种权益类型最多选择一个；
- 地址或权益变化后立即清除旧试算；
- 读取或试算失败时显示明确错误与重试入口；
- 页面固定声明“不会创建订单、锁定优惠或预占库存”。

## 3. Marketing 无副作用试算

`marketing-service` 新增顾客身份保护的价格试算接口。它复用正式锁价的：

- 用户权益归属与 `AVAILABLE` 校验；
- 有效期、使用门槛和地区资格；
- Coupon、Red Packet、Subsidy 叠加顺序；
- 每类型最多一份权益；
- 商品行最大余数法逐分分摊。

试算与正式锁价的差异是没有任何持久化副作用：

- 不生成 `pricing_lock`；
- 不生成价格锁权益或分摊记录；
- 不把权益改为 `LOCKED`；
- 不发布订单或营销生命周期事件。

自动化测试还用同一输入执行正式锁价，证明试算与锁价金额一致，而不是维护第二套计算逻辑。

## 4. 浏览器业务 ID 契约

本批继续使用 DTO 局部 `ToStringSerializer`，没有全局修改 ObjectMapper。

当前已推进：

- Identity：User、Address；
- Trade：Cart、订单地址快照、订单项、优惠分摊、售后；
- Inventory：Warehouse、Stock、Reservation、ReturnStock；
- Marketing：Benefit 用户、试算/锁价分摊 SKU、价格锁用户。

第三批真实 HTTP 实际覆盖 User、Address、Cart Product/SKU、Benefit User 与 Preview Allocation SKU。Inventory 本批只完成自动化契约，不宣称已进入真实结算提交链路。Payment、Fulfillment 等页面仍需在接入时逐域审查。

## 5. 自动化门禁

前端执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\frontend
pnpm check
```

结果：

| 包 | 测试 |
| --- | ---: |
| foundation | 7 |
| storefront | 13 |
| admin | 1 |
| 合计 | 21 |

两端类型检查和生产构建同时通过。

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
| trade-service | 58 |
| payment-service | 29 |
| fulfillment-service | 12 |
| marketing-service | 7 |
| 合计 | 150 |

共 43 份 Surefire 报告，0 失败、0 错误、0 跳过。PMD Maven Plugin 3.28.0 / PMD 7.17.0 全 Reactor 通过。

## 6. 浏览器验收

受控 Mock API 夹具完成以下交互验收：

- 刷新页面后的会话恢复；
- 地址新增、编辑焦点、默认地址切换；
- 删除确认、取消删除和最终删除；
- `/bag` 显示账户购物车 2 件、¥378.00；
- `/checkout` 使用 `COUPON-10` 后显示 ¥368.00；
- 页面明确声明不创建订单、不锁优惠、不预占库存；
- CUSTOMER 登录管理端后进入 `/forbidden`；
- User、Address、SKU、Marketing 分摊等超大 ID 始终为 JSON string；
- 浏览器 console 无 error。

`frontend/e2e/mock-api.mjs` 只用于交互、状态和超大 ID 的浏览器验收，不参与生产构建，也不替代真实中间件。

## 7. 真实最小链路

真实验证范围：

- MySQL；
- Redis；
- Nacos；
- Gateway；
- Identity；
- Trade；
- Marketing。

没有启动八服务全套、RocketMQ、MinIO 或观测栈。Trade 的 Outbox、五类消费者、订单恢复与对账，以及 Marketing 订单消费者，均使用 Spring Boot 命令行参数最高优先级关闭。

首次仅用环境变量关闭 MQ 时，Nacos 配置覆盖后服务仍尝试连接 RocketMQ。该轮业务没有伪造成功，但不能作为干净证据。最终复跑改用命令行参数，结果为：

- Gateway、Identity、Trade、Marketing 均注册到 Nacos 且健康；
- Nacos 配置源和 Gateway Request ID 正常；
- 地址 CRUD 全部通过真实 Gateway；
- 新账户购物车为 0 项；
- 验收账户购物车为 2 件、原价 ¥378.00；
- `COUPON-10` 优惠 ¥10.00，应付 ¥368.00；
- `pricing_lock` 试算前后均为 0；
- 当前用户价格锁为 0；
- 权益保持 `AVAILABLE`，锁定计数为 0；
- Trade MQ 日志 0 行；
- Marketing MQ 日志 0 行；
- Application error 0 行；
- stderr 总字节 0。

临时用户、地址、购物车、Marketing 规则和权益均精确清理为 0。四个 JVM、相关容器和本次启动的 Docker Desktop 已停止，环境恢复到接管前状态。

## 8. 下一批边界

下一批先完成订单提交前权威复核：

1. 重新读取 Catalog 当前价格和商品状态。
2. 查询 Inventory 权威可用库存，但仍以订单提交时的 MySQL 预占结果作为最终裁决。
3. 重新执行 Marketing 当前资格与价格计算，处理草稿已变化。
4. 固定订单请求快照和稳定 `Idempotency-Key`，接通 Trade 幂等提交。
5. 对超时、断连和响应丢失按订单查询恢复，不能换键盲目重提。

订单结果页、列表/详情和取消稳定后再进入 Payment。支付页面必须保留“处理中/结果未知”语义，不能把中间件或网络异常转换成成功。
