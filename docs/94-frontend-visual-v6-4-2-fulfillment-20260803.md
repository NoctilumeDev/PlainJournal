# 前端视觉 V6.4.2：Fulfillment 第二代表页

> 完成日期：2026-08-03  
> 状态：V6.4.2 已完成；下一批为 V6.4.3 其余管理工作区迁移  
> 范围：正向履约、物流轨迹、异常恢复、Redis GEO 投影、退货收货与验收  
> 硬边界：未修改 Fulfillment API、后端状态机、幂等规则、权限或所有者域裁决

## 1. 代表页选择

本批先只读比较：

- `InventoryWorkspaceView`；
- `FulfillmentWorkspaceView`；
- 两套 Foundation API；
- Inventory/Fulfillment Controller、Service、Security 和集成测试；
- 管理端旧 CSS 的消费者。

Inventory 主要验证仓库、库存查询和一类幂等调整命令。Fulfillment 同时覆盖：

- `CREATED -> PICKING -> PACKED -> SHIPPED -> IN_TRANSIT -> DELIVERING -> SIGNED`；
- `EXCEPTION` 标记与仅 ADMIN 可执行的恢复；
- 物流轨迹 `externalEventId` 幂等；
- MySQL 空间事实和 Redis GEO 可重建投影；
- `RETURNING -> RECEIVED -> INSPECTED`；
- 退货验收后跨域库存回补和退款推进。

因此 V6.4.2 只选择 Fulfillment 作为第二代表页，没有同时修改 Inventory。该选择能
检验管理端积木是否承受多状态、高命令密度和正逆向组合，又不扩大批次风险。

## 2. 代码边界

迁移后依赖方向为：

```text
Fulfillment Foundation API
            ↓
admin entities/admin-fulfillment
  - owner/token access revision
  - forward / reverse facts
  - pending command persistence
  - unknown / accepted / rejected
  - authoritative reread / exact retry
  - GEO projection operations
            ↓
FulfillmentWorkspaceView
  - page composition
  - fields / actions / status rendering
  - continuous forward and reverse journey
```

旧 view 中的 API 创建、随机命令生成、异步编排、失败分类、列表 upsert 和
`globalThis.prompt` 已移除。页面只消费 entity 公开入口和共享 UI primitives。

分层规则新增：

- view 只能引用 `entities/admin-fulfillment/index.ts`；
- 禁止绕过公共入口读取 model；
- entity 禁止读取 legacy session store；
- 员工身份、token 和权限通过显式 context 注入。

## 3. 结果未知与命令恢复

### 3.1 pending 内容

任何写命令在发出前先按 `operatorId` 保存：

```text
kind
referenceNo
commandKey
payload
createdAt
```

当前页面一次只允许一条未确认命令。unknown 未收敛前，其他命令被阻断，避免在同一
履约单上继续生成第二个事件 ID 或恢复命令 ID。

### 3.2 失败分类

| 响应 | 页面状态 | pending | 后续动作 |
| --- | --- | --- | --- |
| 2xx 且业务号匹配 | `accepted` | 清除 | 更新权威事实并准备下一步 |
| 明确 4xx / 业务拒绝 | `rejected` | 清除 | 显示明确拒绝 |
| 网络、超时、非法响应、5xx | `unknown` | 保留 | 读取权威事实或原样重试 |
| 2xx 但业务号错归 | `unknown` | 保留 | 视为契约异常 |
| 权威 GET 失败 | `unknown` | 保留 | 不显示成功 |

### 3.3 不同命令的确认边界

| 命令 | 可观察身份 | 权威确认方式 |
| --- | --- | --- |
| 拣货 / 打包 | 状态历史 command + operator | 读取单个履约事实 |
| 发货 | carrier + trackingNo + `SHIP` 历史 | 读取单个履约事实 |
| 物流轨迹 | `externalEventId` + 完整轨迹载荷 | 读取 traces 精确匹配 |
| 标记异常 | 状态 + `MARK_EXCEPTION` reason/operator | 读取单个履约事实 |
| 异常恢复 | `Idempotency-Key` 不在响应中公开 | 只读状态不能归因；必须原键 POST 确认 |
| 退货收货 | `RECEIVED/INSPECTED` 状态 | 读取单个退货事实 |
| 退货验收 | `INSPECTED` + inspectionRemark | 读取单个退货事实 |

异常恢复是本批最重要的“不盲信当前状态”边界。第一次 POST 可能已经提交并丢失响应，
随后 GET 可看到 `PICKING`，但响应 DTO 不含恢复命令 ID。页面不能据此声称“原命令
成功”，只能显示当前权威状态仍无法归因，并使用原 `Idempotency-Key + reason` 重试。

## 4. 页面与视觉结构

页面使用：

- `PjPageContainer`；
- `PjSurface`；
- `PjField`；
- `PjButton`；
- `PjActionGroup`；
- `PjStatusNotice`。

页面按连续事实组织，而不是按后端组件堆卡片：

1. 全局履约边界与筛选；
2. MySQL 空间事实 / Redis GEO 投影；
3. 正向拣货、打包、发货、轨迹、异常与恢复；
4. 逆向退货收货与验收。

旧弹窗异常原因改为页面内必填字段，使原因、风险说明和提交动作保持在同一上下文。
状态历史按需展开，不与当前下一步争抢视觉优先级。

页面迁移后，`admin-geo-query` 与 `admin-geo-result` 已无消费者，从 `admin.css` 删除。
其余旧管理类仍被 Inventory、Marketing、After-sale、Chat 等页面使用，未提前清理。

## 5. 浏览器发现的隐藏缺陷

首轮 V6.4 浏览器专项中，物流轨迹提交没有发出请求，Console 出现：

```text
TypeError: form.longitude.trim is not a function
```

原因是 Vue 对 `type="number"` 的 `v-model` 在真实浏览器中产生 number，而原 store
把坐标写成 string 并直接调用 `.trim()`。原单测使用字符串输入，因此没有覆盖该运行时
边界。

修复：

- 坐标、半径和结果上限建模为 `string | number`；
- 进入命令和查询前统一执行 `String(value ?? "").trim()`；
- entity 单测改用 number 坐标；
- 重新运行专项和全量浏览器链。

这证明浏览器/F12 门禁不能由类型检查和单元测试替代。

## 6. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实运行 / 浏览器证据 |
| --- | --- | --- | --- |
| 状态机与权限 | `FulfillmentService` 锁行转换；Security 仅允许 ADMIN 恢复异常 | 后端 `FulfillmentFlowIntegrationTest` 覆盖跳态、角色、幂等、并发恢复 | 既有真实异常恢复证据见第 69 号文档；本批浏览器只显示当前角色允许的动作 |
| 轨迹事件幂等 | backend 按 `externalEventId + requestHash` 裁决；前端 pending 保存完整载荷 | entity 测试覆盖 503、重建恢复、权威精确匹配与同载荷重试 | Playwright 捕获两次 POST 的 event ID、description、occurredAt 完全一致，Mock 权威端只有一条命令、attempts=2 |
| 异常恢复原键 | backend 按 `Idempotency-Key + requestHash + fulfillment` 幂等 | 后端覆盖同键重放、变载荷冲突和并发；entity 覆盖两次同键同原因 | 浏览器先看到 GET 已为 PICKING 但仍保持 unknown，再以同一 key/reason POST 后才 accepted |
| unknown 不伪造成功 | `resultMayBeUnknown` 只把不确定失败归入 unknown | entity 覆盖 503、409、错归、owner switch | 503 后 success 数量为 0；请求与 Console 监听确认只出现预期 503 |
| GEO 最终事实 | backend MySQL Spatial 裁决、Redis 只作投影 | Fulfillment 后端 GEO 集成分支已封存 | 真实 MySQL/Redis 删除、暂停、回主和重建证据见第 64、69 号文档 |
| 退货与库存/退款边界 | `ReturnReceiptService` 只发布 `ReturnInspected`，不直接改库存或退款 | `ReturnReceiptFlowIntegrationTest` 覆盖收货、验收、Outbox 和权限 | 真实逆向链、库存回补和退款证据见第 16、69 号文档 |

受控 Mock 只复现浏览器响应丢失和请求取证，不替代 MySQL、Redis、RocketMQ。由于本批
未修改后端契约和状态机，真实中间件证据继续引用既有 M0–M8 封存结果；当前新增证据
专门证明前端不会把这些事实画反。

## 7. 最终门禁

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 45 / 45 |
| UI primitives | 5 / 5 |
| Storefront | 171 / 171 |
| Admin | 25 / 25 |
| 前端单元/契约测试合计 | 252 / 252 |
| 分层规则 | 21 / 21 |
| 分层文件 / 相对导入 | 126 / 242 |
| Playwright 全量 Mock E2E | 45 / 45 |
| V6.4 专项 Playwright | 4 / 4 |
| Fulfillment 后端专项 | 19 / 19 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 101.19 kB / gzip 14.33 kB
Storefront JS  356.90 kB / gzip 107.63 kB
Admin CSS      39.55 kB / gzip 6.65 kB
Admin JS      229.99 kB / gzip 72.67 kB
```

全量 E2E 首轮为 44/45，唯一失败是 M4 旧测试仍查找迁移前 GEO 文案和
`.admin-work-card`。更新为新事实文案和 `.fulfillment-record` 后，第二轮又发现
V6.3 通知用例退出后立即重登录的导航断言错误。核对产品实现后确认成功退出的正式
落点是匿名首页 `/`，不是登录页。测试改为等待匿名首页，再显式进入 `/login` 登录
另一账户；V6.2、V6.3 专项和全量串行复验均通过，没有发现浏览器 context 或 refresh
token 跨测试泄漏。全量最终为 45/45。

最终清理：

- `18090/18200/18201` 无监听；
- Mock API、Storefront Vite、Admin Vite 无残留；
- 未启动 Docker 或项目中间件；
- Maven 专项结束后无项目 Java 服务常驻；
- 未修改代理、网卡、机器级 Node 或 Docker 数据。

## 8. 下一坐标

V6.4.3 继续迁移其余管理端页面，不进入 V7：

1. Inventory；
2. Catalog / Marketing；
3. After-sale / Review；
4. Chat / 管理首页；
5. 只有两个以上页面证明同一布局模式后，才抽取共享管理端 primitive；
6. 每迁移一个真实消费者，再删除对应零消费者旧 CSS。
