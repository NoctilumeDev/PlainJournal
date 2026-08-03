# 前端视觉 V6.4.3：Marketing 管理工作区

> 完成日期：2026-08-03  
> 状态：V6.4.3 Marketing 切片已完成；V6.4.3 仍剩 Catalog、After-sale、Review、Chat 与管理首页  
> 范围：营销规则创建、幂等权益发放、结果未知恢复、员工账户隔离  
> 硬边界：未修改 Marketing API、价格计算、权益生命周期、订单事件消费或 MySQL 最终事实

## 1. 迁移范围

本批只迁移 `MarketingWorkspaceView`。旧页面直接持有 API、表单、异步编排和错误解释，
并把所有异常压成同一条错误反馈。迁移后依赖方向为：

```text
Marketing Foundation API
          ↓
entities/admin-marketing
  - operator/token access revision
  - rule and grant forms
  - pending command persistence
  - unknown / accepted / rejected
  - exact grant-key retry
          ↓
MarketingWorkspaceView
  - continuous fact composition
  - fields / actions / status rendering
```

页面只从 `entities/admin-marketing/index.ts` 使用公开入口。entity 不读取旧 session store，
而是显式接收 `authorized + operatorId + accessToken`。员工或 token 切换后，旧请求响应
只能静默作废，不能把前一账户的规则、权益或 pending 内容写入当前页面。

## 2. 两种命令不能使用同一种恢复策略

### 2.1 规则创建

`POST /api/v1/marketing/admin/rules` 没有稳定幂等命令键。`rule_code` 虽有唯一约束，
但当前没有管理端规则列表或按代码查询 API。因此：

- 5xx、网络、超时、非法响应和返回事实错归保持 `unknown`；
- 完整保存代码、名称、类型、金额、顺序、有效期和地区载荷；
- 不提供重复 POST 按钮；
- 后续出现 `DUPLICATE_RESOURCE` 也不能反推原命令成功，因为规则可能在首次操作前
  已经存在；
- 只有原 POST 的权威成功响应能够让当前页面显示 `accepted`。

这不是前端缺少一个按钮，而是后端恢复契约目前没有足够事实。页面必须把缺口公开，
不能用唯一约束冲突伪造成功。

### 2.2 权益发放

`POST /api/v1/marketing/admin/benefits` 的命令身份是：

```text
userId + grantKey + ruleCode
```

数据库以 `(user_id, grant_key)` 唯一约束裁决；服务读取原记录后继续校验 `rule_id`：

- 同顾客、同 `grantKey`、同规则返回原权益；
- 同顾客、同 `grantKey`、不同规则返回 `IDEMPOTENCY_CONFLICT`；
- 不同顾客可以使用相同 `grantKey`，因为所有者不同；
- 响应丢失后只允许原顾客、原规则、原 `grantKey` 重试。

前端在 POST 前按员工保存三项身份和完整载荷。pending 存在时字段只读并阻止第二条
命令，重建 store 后仍恢复原 `grantKey`，不会生成第二份权益。

## 3. 返回事实校验

规则成功响应必须逐项匹配当前命令：

- 规则代码、名称、权益类型；
- 门槛和优惠金额；
- 叠加顺序；
- 开始与结束时间；
- 地区层级与代码。

金额使用两位小数的最小单位比较，时间按时间戳比较，地区集合排序后比较。权益响应必须
匹配当前 `userId + ruleCode` 且包含权益号。任何错归都进入 `unknown`，不会渲染为
本次创建或发放事实。

## 4. 页面结构

页面按同一条营销事实链组织：

1. 两类命令的恢复边界；
2. 创建优惠规则；
3. 本次规则权威返回；
4. 向顾客发放权益；
5. 本次权益权威返回。

页面使用：

- `PjPageContainer`；
- `PjSurface`；
- `PjField`；
- `PjButton`；
- `PjActionGroup`；
- `PjStatusNotice`。

不再使用旧 `.admin-work-grid` 把规则与权益做成并列服务卡片。窄屏下表单和事实网格
收敛为单列，320px 不产生根级横向溢出。

## 5. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实浏览器运行证据 |
| --- | --- | --- | --- |
| 权益发放幂等 | `MarketingService.grantBenefit` 读取 `(userId, grantKey)` 原记录并核对规则；迁移脚本有 `uk_user_benefit_grant` | 新增后端测试断言同键同规则返回同一权益、同键换规则冲突、不同顾客隔离；entity 覆盖 503、重建恢复、409 和原键重试 | Playwright 捕获两次 POST 的顾客、规则和 `grantKey` 逐字段一致；受控权威端只生成一个 Benefit，attempts=2 |
| 规则创建结果未知 | Controller 只有 POST，没有管理端规则 GET；数据库仅提供 `rule_code` 唯一约束；entity 禁止 rule pending 重试 | entity 断言 503 后保持 unknown，调用 retry 也不会产生第二次 fetch | Playwright 中服务端已保存规则但返回 503；页面不出现 success、不显示重试按钮，浏览器只发出一次 POST |
| 返回事实不可错归 | entity 对规则完整载荷和权益 owner/rule 做契约校验 | 单元测试分别返回错误金额和错误顾客，均保持 unknown 且不写入事实 | 两条浏览器链只在当前命令身份与返回事实一致时显示 accepted |
| 员工与 token 隔离 | entity 使用 `operatorId + accessToken + accessRevision` 判定响应是否仍属于当前页面 | 单元测试覆盖 token 更新后旧规则响应作废，以及 operator 切换后 pending/fact 隔离 | 管理端通过真实路由守卫和登录流程进入 Marketing；pending 字段在当前员工上下文中保持只读 |
| 权限边界 | `MarketingSecurityConfig` 只允许 ADMIN/OPERATOR 管理命令 | `MarketingFlowIntegrationTest.protectsAdminAndInternalRoutes` 验证 CUSTOMER 被拒绝 | Playwright 使用 ADMIN 账号进入真实管理路由并完成命令链 |

浏览器使用真实 Chromium、Vue、Pinia、Foundation client 和 HTTP 请求，只在服务端响应
丢失故障注入处使用受控 Mock。Mock 用于取证请求身份与 UI 语义，不替代数据库正确性。
本批后端专项使用 H2 的 MySQL 兼容模式验证事务、唯一约束和权限；未启动全套中间件。
真实 MySQL、Redis、RocketMQ 下的 Marketing 锁价、核销、释放和秒杀链证据继续引用
[营销价格服务](15-marketing-service.md) 与
[M0–M8 三层审查](69-m0-m8-pre-m9-three-layer-audit-20260728.md)。

## 6. 最终门禁

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 45 / 45 |
| UI primitives | 5 / 5 |
| Storefront | 171 / 171 |
| Admin | 37 / 37 |
| 前端单元/契约测试合计 | 264 / 264 |
| 分层规则 | 23 / 23 |
| 分层文件 / 相对导入 | 132 / 246 |
| Playwright 全量 Mock E2E | 49 / 49 |
| V6.4 专项 Playwright | 8 / 8 |
| Marketing 后端专项 | 8 / 8 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 101.19 kB / gzip 14.33 kB
Storefront JS  356.90 kB / gzip 107.63 kB
Admin CSS      45.99 kB / gzip 7.23 kB
Admin JS      259.18 kB / gzip 79.26 kB
```

Marketing 迁移后确认 `.admin-work-grid` 已无任何 Vue、TS 或 E2E 消费者，连同窄屏覆盖
一起删除。After-sale、Chat、Review 与管理首页仍消费的 `.admin-work-card`、
`.admin-inline-form`、`.admin-fact-grid`、`.admin-feedback` 等规则继续保留。

## 7. 下一坐标

V6.4.3 下一最小切片为 Catalog。当前 Catalog 页只展示公开 ACTIVE 商品的只读投影，
业务风险低于 Marketing，但仍需先核对公开投影、员工权限、分页/筛选和图片事实，再决定
是抽离只读 entity，还是直接迁移页面组合。

本批不迁移 Catalog、After-sale、Review、Chat 或管理首页，也不进入 V7。
