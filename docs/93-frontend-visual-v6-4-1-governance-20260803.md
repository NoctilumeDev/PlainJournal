# 前端视觉 V6.4.1：Governance 高风险代表页

> 完成日期：2026-08-03  
> 状态：V6.4.1 已完成；下一批为 V6.4.2 其余管理工作区迁移  
> 范围：四域只读对账、退款渠道重派、异常支付退款、稳定命令 ID、结果未知与追加式审计  
> 硬边界：未修改 Payment API、退款状态机、幂等规则、权限或所有者域裁决

## 1. 本批结论

V6.4 没有直接批量改全部管理页面。第一批先选择风险和信息密度最高的
`GovernanceWorkspaceView` 作为管理端原型，验证同一套设计语言能否同时承载：

- Trade、Payment、Inventory、Fulfillment 四域只读对账；
- Payment 退款派发补偿；
- Payment 异常支付退款；
- 操作原因、稳定命令 ID、追加式审计；
- 明确拒绝、结果未知、权威收敛和安全重试。

迁移后的代码边界为：

```text
Payment / Governance Foundation API
              ↓
admin entities/governance
  - access revision
  - pending command persistence
  - unknown / accepted / rejected
  - audit convergence
              ↓
GovernanceWorkspaceView
  - page composition
  - fields / actions / tables
  - semantic status rendering
```

页面不再直接持有全部异步流程和恢复判断。它只绑定 entity 已确认的状态和事实。

## 2. 补偿命令状态机

两类命令使用相同原则，但保持独立 pending 状态：

```text
退款渠道重派
  referenceNo + commandId + reason

异常支付退款
  referenceNo + commandId + reason
```

首次提交前，三项数据按管理员 `operatorId` 写入本地 pending。页面刷新或 token 轮换
不会生成新命令 ID，也不会丢失原因。

### 2.1 失败分类

| 响应 | 页面状态 | pending | 后续动作 |
| --- | --- | --- | --- |
| 明确 2xx 且返回事实匹配 | `accepted` | 清除 | 可查看审计或准备新命令 |
| 明确 4xx / 业务拒绝 | `rejected` | 清除 | 显示明确拒绝，不冒充 unknown |
| 网络、超时、非法响应、5xx | `unknown` | 保留 | 优先读取权威审计 |
| 2xx 但退款号或支付号不匹配 | `unknown` | 保留 | 视为契约异常，不接受错误归属事实 |
| 审计 GET 失败 | `unknown` | 保留 | 不清除命令，不显示成功 |

对 unknown 命令：

- 业务编号、命令 ID 和原因变为只读；
- “准备新命令”被拒绝；
- 审计只接受同一 `commandId + referenceNo + reason`；
- `ACCEPTED` 和 `REJECTED` 才能收敛；
- 审计没有当前命令时继续 unknown；
- 必须重试时继续使用原命令 ID 和原原因。

这样关闭了旧页面中“所有异常都显示 danger，但文案又说结果未知”的语义冲突。

## 3. 管理端视觉原型

Governance 页面迁移到：

- `PjPageContainer`；
- `PjSurface`；
- `PjField`；
- `PjButton`；
- `PjActionGroup`；
- `PjStatusNotice`。

页面按三个连续区域组织：

1. 四域只读对账；
2. 退款渠道重派；
3. 异常支付全额原路退款。

服务所有权、稳定命令 ID 和审计仍清楚可见，但不再使用旧
`admin-work-card + admin-inline-form + admin-feedback` 组合制造多个互相竞争的面板。
其他管理页面仍在使用这些旧类，因此本批没有提前删除共享规则。

管理表格保留自身横向滚动，不允许最小内容宽度撑大根页面。320、390 和 1280px
均验证根级横向溢出为 0。

## 4. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实运行 / 浏览器证据 |
| --- | --- | --- | --- |
| 5xx 不伪造拒绝或成功 | `resultMayBeUnknown` 只将网络、超时、非法响应、5xx 和契约错归为 unknown | entity 测试覆盖 503、网络语义和错误归属响应 | 内置浏览器提交后先显示 unknown；既有 Payment 真实补偿证据见第 19、20、69 号文档 |
| 稳定命令 ID 与原因 | pending 在 POST 前按 operator 持久化；unknown 时字段只读 | 测试覆盖 store 重建恢复与两次 POST 同键同原因 | Playwright 捕获两次相同 `Idempotency-Key`；内置浏览器命令 ID 与 Mock 权威记录逐字符一致 |
| 审计权威收敛 | 只匹配同一 command/reference/reason，且只识别 ACCEPTED/REJECTED | 测试覆盖 ACCEPTED、REJECTED、审计缺失和审计 GET 失败 | 浏览器在 503 后不显示 success，点击“读取权威审计”后才收敛为 ACCEPTED |
| 明确 4xx | 4xx 不进入 unknown 分支，并清除 pending | 测试覆盖 409 `REFUND_RETRY_NOT_ALLOWED` | 后端真实拒绝与追加审计语义已由 Payment 集成测试和第 19 号文档封存 |
| 管理员作用域 | pending key 按 `operatorId` 隔离；access revision 拒绝迟到响应 | 分层规则禁止 Governance entity 读取 legacy session store；store 测试使用显式上下文 | 真实管理路由仍由 ADMIN guard 保护；既有顾客补偿 403、管理员补偿 200 证据见第 19、69 号文档 |
| 页面不直接改写资金事实 | entity 仅调用现有 Payment API；页面无状态机写入或成功覆盖 | Foundation 契约测试继续验证 API 路径和请求头 | 真实 Payment MySQL、审计和退款派发证据沿用 M2/M8 封存结果 |

受控 Mock 用于复现浏览器响应丢失和网络检查，不替代真实 Payment/MySQL。由于本批未
修改后端契约或状态机，资金侧真实证据引用已经完成的 Payment 授权补偿、对账和
M0–M8 三层审查；当前新增证据专门证明前端不会把该真实语义画反。

## 5. 浏览器与 F12 等价取证

新增 V6.4 专项 Mock 状态：

- `audit-confirmed`：Payment 事务已提交、POST 返回 503、审计已经存在；
- `retry-required`：首次 POST 返回 503、审计暂未出现、第二次 POST 必须复用原键。

专项 Playwright 验证：

- 503 后 `success` 数量为 0；
- reference、reason 在 unknown 时为只读；
- 审计 GET 后才显示 ACCEPTED；
- 审计未出现时继续 unknown；
- 两次 POST 的 `Idempotency-Key` 和 reason 完全一致；
- Mock 权威端只记录一个命令，尝试次数为 2；
- 320、390、1280px 根级横向溢出为 0；
- axe serious/critical 为 0；
- Console warning/error 和意外 HTTP 错误为 0。

内置浏览器再次执行 `audit-confirmed`：

```text
commandId =
refund-retry:33c61c4c-1c7b-43f2-88b1-97bf307a766b

页面：
命令结果未知
  -> 读取权威审计
  -> ACCEPTED
  -> NEEDS_ATTENTION → PENDING

Mock 权威记录：
referenceNo = RF-V64-NEEDS-ATTENTION
reason = 内置浏览器验证：响应丢失后只允许审计收敛
attempts = 1
```

内置浏览器可见宽度为 802px，`clientWidth = scrollWidth = 802`，Console
warning/error 为 0。

## 6. 本批发现并关闭的问题

### 6.1 管理表格撑大根页面

首轮 390px 浏览器门禁得到：

```text
clientWidth = 390
scrollWidth = 940
```

原因不是表格缺少滚动条，而是包含表格的 `PjSurface` grid item 仍使用 min-content
宽度。修复为父级 surface、表单子项、事实子项和状态正文显式 `min-width: 0`，表格
滚动保留在自己的容器内。

### 6.2 HTML pattern 在 Unicode v 模式失效

旧表达：

```html
pattern="[A-Za-z0-9._:-]+"
```

在新版 Chrome 的 `v` 正则模式中，字符类内连字符必须转义。该问题会产生 Console
SyntaxError，并使浏览器校验失真。Governance 与旧 Fulfillment 跟踪号字段均改为：

```html
pattern="[A-Za-z0-9._:\-]+"
```

### 6.3 浏览器回归测试兼容

全量回归还关闭三项测试债务：

- M4 管理端用例不再断言已删除的旧治理文案；
- V6.4 使用绝对管理端 URL，避免全量配置的顾客端 baseURL；
- V6.2 账户切换在退出后等待匿名首页，避免仍有效会话把 `/login` 重定向走。

V6.2 专项在修复后连续两轮 3/3 通过。

### 6.4 单机资源控制

`tools/run-e2e.ps1` 新增 `-AdminOnly`，V6.4 专项只启动：

```text
Mock API 18090
Admin Vite 18201
```

不再为管理端专项同时启动顾客端。人工浏览器验证结束后，两个进程按监听端口和命令行
核对后停止。

## 7. 最终自动化门禁

最终一次 `pnpm check`：

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 45 / 45 |
| UI primitives | 5 / 5 |
| Storefront | 171 / 171 |
| Admin | 19 / 19 |
| 前端单元/契约测试合计 | 246 / 246 |
| 分层规则 | 20 / 20 |
| 分层文件 / 相对导入 | 123 / 240 |
| Playwright 全量 Mock E2E | 43 / 43 |
| V6.4 专项 Playwright | 2 / 2 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 101.19 kB / gzip 14.33 kB
Storefront JS  356.90 kB / gzip 107.63 kB
Admin CSS      34.10 kB / gzip 6.13 kB
Admin JS      211.44 kB / gzip 67.66 kB
```

最终清理：

- `18090/18200/18201` 无监听；
- Mock API、Storefront Vite、Admin Vite 无残留；
- 未启动 Docker、Java 或项目中间件；
- 未修改代理、网卡、机器级 Node 或 Docker 数据。

## 8. 下一坐标

V6.4.2 继续管理端，但不立即创造万能后台组件：

1. 选择 Inventory/Fulfillment 中一个命令与表格并存的第二代表页；
2. 证明 Governance 的表格、筛选、命令区模式确实可复用后，再上升共享管理 primitive；
3. 迁移 Catalog、Marketing、After-sale、Review、Chat 和首页；
4. 每迁移一个消费者，才删除对应零消费者旧 CSS；
5. V6.4 全部完成后再进入 V7，不进入多商户或 Go。
