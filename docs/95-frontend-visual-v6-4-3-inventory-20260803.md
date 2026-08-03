# 前端视觉 V6.4.3：Inventory 管理工作区

> 完成日期：2026-08-03  
> 状态：V6.4.3 Inventory 切片已完成；V6.4.3 仍剩 Catalog、Marketing、After-sale、Review、Chat 与管理首页  
> 范围：仓库事实、库存位置查询、幂等库存调整、结果未知恢复  
> 硬边界：未修改 Inventory API、MySQL 裁决、状态机、权限、Outbox 或 Redis 定位

## 1. 迁移范围

本批只迁移 `InventoryWorkspaceView`，没有同时修改 Catalog 或 Marketing。旧页面把 API
创建、随机流水号、异步状态和错误解释全部放在 view 中，并把网络失败统一显示为普通
错误。迁移后依赖方向为：

```text
Inventory Foundation API
          ↓
entities/admin-inventory
  - operator/token access revision
  - warehouse and stock facts
  - pending command persistence
  - unknown / accepted / rejected
  - authoritative reread / exact movement retry
          ↓
InventoryWorkspaceView
  - page composition
  - fields / actions / status rendering
```

页面显式接收员工 `operatorId + accessToken + authorized` 上下文。员工账户或 token
切换后，旧请求响应只能静默作废，不能写入新账户工作区。

## 2. 库存调整恢复边界

Inventory 后端使用：

```text
movementNo
+ warehouseId
+ skuId
+ quantityDelta
+ reason
→ requestHash
```

同一 `movementNo` 只有完整载荷一致时才能重放。前端因此在 POST 前按员工保存：

- `movementNo`；
- 仓库 ID；
- SKU ID；
- 数量变化；
- 原始原因；
- 创建时间。

网络、超时、非法响应和 5xx 保持 `unknown`，不生成第二个流水号。明确 4xx 视为
`rejected`。结果未知时：

1. 可以读取当前库存；
2. 当前库存只更新页面事实，不能收敛原命令；
3. 只有使用原 `movementNo + 完整载荷` 重试并收到权威响应，才能显示成功。

普通 `StockPosition` DTO 只有仓库、SKU、在手、预占、可用和版本，不公开
`movementNo`。因此即使响应丢失后重读看到在手从 10 变成 15，也不能据此把原调整
画成成功。这是本批最高风险边界。

## 3. 仓库创建边界

仓库创建接口没有额外幂等命令键。结果未知时，页面保存规范化后的唯一仓库代码和名称，
只允许读取仓库列表核对：

- 代码与名称均一致：目标仓库事实已经存在，可以收敛；
- 代码存在但名称不同：不能归因原命令；
- 代码不存在：继续保持 unknown。

页面不提供仓库创建的盲目重复 POST 按钮，避免把唯一约束冲突误解释为首次创建失败。

## 4. 页面结构

页面按连续事实组织：

1. Inventory 最终裁决说明；
2. 当前经营仓库；
3. MySQL 库存位置；
4. 幂等库存调整。

仓库和库存不再使用旧 `.admin-work-card` 拼装后台卡片墙，而是使用：

- `PjPageContainer`；
- `PjSurface`；
- `PjField`；
- `PjButton`；
- `PjActionGroup`；
- `PjStatusNotice`。

库存调整 pending 存在时，业务号与载荷字段只读，并阻止第二条命令。

## 5. 浏览器发现的隐藏缺陷

首轮 Inventory 浏览器专项没有进入业务提交，原因是测试字段定位不够精确。页面同时
存在查询和调整两组“仓库 ID / SKU ID”，测试改为使用稳定表单 ID，而不是依赖同名
label 的位置顺序。

第二轮业务链通过，但 Console 出现：

```text
Pattern attribute value [A-Za-z0-9_-]+ is not a valid regular expression
```

现代 Chromium 按 HTML pattern 的 `v` 模式解析正则，字符类中的连字符必须显式转义。
仓库代码表达式已改为：

```text
[A-Za-z0-9_\-]+
```

类型检查和 Vue 单测不会执行浏览器原生约束校验，这一问题只能由真实浏览器 Console
门禁发现。

## 6. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实运行 / 浏览器证据 |
| --- | --- | --- | --- |
| MySQL 最终库存 | `InventoryService.adjustStock` 在事务内锁定 adjustment 与 balance，条件更新后写流水和 Outbox | `InventoryFlowIntegrationTest` 17/17，包含 1000 并发预占且无负库存或超卖 | 真实 MySQL 库存、回补和对账证据继续引用第 21、69 号文档 |
| movementNo 幂等 | 后端保存 requestHash；前端 pending 保存原流水和完整载荷 | entity 覆盖 503、重建恢复、同载荷重试与 owner switch | Playwright 捕获两次 POST 完全一致；Mock 权威端只应用一次，attempts=2 |
| unknown 不伪造成功 | 库存 GET 不包含 movementNo；前端重读只更新 stock，保持 pending/unknown | entity 明确断言重读在手和版本后仍 unknown | 浏览器看到在手 15 仍不出现 success，原流水重试后才 accepted |
| 仓库创建响应丢失 | 前端按唯一 code/name 保存并只读核对 | entity 覆盖 503 后列表精确匹配 | Playwright 中创建 POST 只发生一次，列表重读后才显示成功 |
| 权限与账户隔离 | Security 仅允许 ADMIN/WAREHOUSE；entity 使用 operator/token 代次 | 后端权限测试与 entity stale response 测试通过 | 管理端路由和浏览器登录只暴露真实权限入口 |

受控 Mock 只用于浏览器响应丢失和请求取证，不替代 MySQL。当前批次未修改后端契约，
所以真实中间件证据引用 M0–M8 已封存结果；当前重新运行的后端专项负责证明代码边界仍
成立。

## 7. 最终门禁

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 45 / 45 |
| UI primitives | 5 / 5 |
| Storefront | 171 / 171 |
| Admin | 30 / 30 |
| 前端单元/契约测试合计 | 257 / 257 |
| 分层规则 | 22 / 22 |
| 分层文件 / 相对导入 | 129 / 244 |
| Playwright 全量 Mock E2E | 47 / 47 |
| V6.4 专项 Playwright | 6 / 6 |
| Inventory 后端专项 | 17 / 17 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 101.19 kB / gzip 14.33 kB
Storefront JS  356.90 kB / gzip 107.63 kB
Admin CSS      43.02 kB / gzip 6.95 kB
Admin JS      244.06 kB / gzip 75.90 kB
```

Inventory 迁移后确认以下旧 CSS 已无消费者并删除：

- `.admin-work-card--full`；
- `.admin-inline-form--columns`；
- `.admin-simple-list`。

Marketing、After-sale、Chat、Review 和管理首页仍消费的 `.admin-work-card`、
`.admin-inline-form`、`.admin-fact-grid` 等规则继续保留，没有提前清理。

## 8. 下一坐标

V6.4.3 下一最小切片建议为 Catalog 与 Marketing 中择一，仍然一次只迁移一个真实
管理工作区。优先顺序由隐藏业务风险决定，不按页面数量批量换皮：

1. 审查 API、权限、命令身份和结果未知边界；
2. 抽离 entity；
3. 迁移真实页面；
4. 浏览器取证；
5. 只删除迁移后零消费者旧 CSS。
