# 前端视觉 V6.4.3：Review 评价治理工作区

> 完成日期：2026-08-03  
> 状态：V6.4.3 Review 切片已完成；V6 仍剩管理端 Chat 与首页  
> 范围：开放/已解决举报列表、平台回复、举报审核、结果未知恢复  
> 硬边界：未修改 Catalog API、评价状态机、评分算法、事务、权限或公开读副本规则

## 1. 核心结论

旧页面把 API 工厂、命令 ID、举报列表、两种写命令、错误和并发状态放在一个 Vue
文件中，并把所有失败压成普通错误。审查后确认两类命令都具备稳定幂等身份：

```text
平台回复
  Idempotency-Key
  -> review_reply.command_id 唯一
  -> review_id 唯一
  -> 同命令、同操作员、同评价、同正文返回原回复

举报审核
  body.commandId
  -> review_moderation_audit.command_id 唯一
  -> 同命令、同操作员、同举报、同结论、同原因返回原审核审计
```

因此网络、超时、非法响应或 5xx 后，前端不显示成功，也不生成新命令。它按管理员保存
举报、评价、商品、评分、原评价正文、命令 ID 和完整写入载荷，只允许原样重放。

管理举报 GET 只返回 `status / resolution / resolvedAt`，不返回审核命令 ID 或审核原因；
公开评价只返回最终回复和可见性。两者都不能证明是哪条命令生效，不能单独作为命令
归因证据。精确恢复必须由原幂等命令重放返回 Catalog 回复或 moderation audit。

## 2. 代码结构

```text
Foundation Catalog API
          ↓
entities/admin-review
  - DTO 运行时校验
  - operator/token access revision
  - OPEN/RESOLVED 分页事实
  - 管理员作用域 pending 持久化
  - 回复与审核的原命令重放
  - 迟到响应和并发命令隔离
          ↓
ReviewWorkspaceView
  - 举报事实与评价证据
  - 一次性平台回复
  - 审核结论与语义状态
```

页面只从 `entities/admin-review/index.ts` 使用 entity，不再直接创建 API。旧
`frontend/admin-web/src/api/reviews.ts` 已零消费者删除。

## 3. 事实与合同校验

entity 对 Catalog 响应执行运行时校验：

- report、review、product、SKU 和 reply ID 必须是十进制字符串，禁止把 64 位雪花 ID
  转为 JavaScript number；
- `OPEN` 必须同时满足 `resolution=null`、`resolvedAt=null`；
- `RESOLVED` 必须有 `UPHELD/REJECTED` 和合法完成时间；
- 举报页码、页大小、筛选状态、总数和唯一 report ID 必须与请求一致；
- 回复 2xx 必须返回同评价、同商品、同评分、同原评价正文、`PUBLISHED` 和同回复正文；
- 审核 2xx 必须返回同 report、review、commandId 和 resolution；
- `UPHELD` 的结果必须是 `HIDDEN`；
- `REJECTED` 必须保持审核前后的评价可见性相同，不能把已隐藏评价重新发布。

任何错 ID、错正文、错命令、错状态迁移或非法时间的 2xx 都保持 unknown，不写入页面。
举报列表刷新失败保留上一次已知事实，不把 503 伪装成空队列。

## 4. 两类恢复协议

### 4.1 平台回复

首次请求前持久化：

```text
operatorId
reportId / reviewId / productId
rating / reviewContent
reply commandId / replyContent
```

5xx 后回复字段只读，所有其他回复和审核命令被阻断。原命令重放后，只有 Catalog 返回
同一评价和同一回复正文才显示 success。后端 `review_reply` 的命令唯一约束与评价唯一
约束保证已提交响应丢失时不会插入第二条回复。

### 4.2 举报审核

首次请求前持久化同一举报快照、`moderationCommandId`、`resolution` 和完整原因。
重放返回的 moderation audit 必须逐字段匹配原命令。

评价可见性与举报结论分开解释：

- `UPHELD + PUBLISHED -> HIDDEN`：本次隐藏评价并扣减一次公开评分；
- `UPHELD + HIDDEN -> HIDDEN`：评价此前已隐藏，本次不重复扣减；
- `REJECTED + PUBLISHED -> PUBLISHED`：举报驳回，评价保持公开；
- `REJECTED + HIDDEN -> HIDDEN`：举报驳回，但不会把其他审核已经隐藏的评价重新发布。

页面不把 `REJECTED` 错译成 `PUBLISHED`，也不把 `UPHELD` 等同于“本次一定扣减评分”。

## 5. 页面组合

页面使用 `PjPageContainer`、`PjSurface`、`PjField`、`PjButton`、
`PjActionGroup` 和 `PjStatusNotice`。每条举报按一条连续事实展示：

```text
举报原因与核对重点
  -> 评价、商品、评分和时间身份
  -> 顾客公开评价与举报说明
  -> 一次性平台回复
  -> Catalog 审核审计
```

`OPEN` 和 `RESOLVED` 使用真实筛选。已解决举报只读展示，不再渲染写入表单。页面同时
支持 ADMIN 与 OPERATOR，与路由和 Catalog SecurityConfig 的权限保持一致。

## 6. 三层证据

| 结论 | 代码证明 | 自动化测试 | 真实 Chromium 运行证据 |
| --- | --- | --- | --- |
| 回复响应丢失不换键、不重复插入 | `ProductReviewService.reply` 校验 command/operator/review/requestHash；数据库同时唯一约束 command 和 review | entity 测试模拟 503 后同 header/body 重放；Catalog 后端专项覆盖同键重放和异载荷/异操作员冲突 | 受控服务先保存回复再返回 503；页面先 unknown，两次请求 commandId/content 完全一致，最终只有一个 reply |
| 审核响应丢失只由原 audit 重放确认 | `resolveReport` 先查 `review_moderation_audit.command_id`，匹配 operator/report/requestHash 后返回原结果 | entity 测试覆盖 503、错 reviewId 2xx、原 commandId/resolution/reason 重放 | Chromium 两次请求体逐字段一致；第一次已解决举报后丢响应，第二次返回同 audit，页面才显示 success |
| 结论与评价可见性不会混淆 | 后端只在 `UPHELD && PUBLISHED` 时隐藏并扣减；REJECTED 不改变 review status | entity 测试覆盖 `REJECTED + HIDDEN -> HIDDEN`；后端专项验证公开列表和汇总扣减 | 浏览器审核成立后公开 review 变 HIDDEN、summary 从 1 变 0；RESOLVED 列表仍显示 UPHELD |
| 64 位身份不会丢精度 | Java DTO 使用 `ToStringSerializer`；entity 对所有业务 ID 运行时要求十进制字符串 | entity 列表与命令测试使用 19 位字符串 ID；Foundation 契约测试覆盖编码路径 | Chromium 页面和受控权威记录逐字符一致显示 report/review/product ID |
| 员工和 token 切换不接收迟到响应 | entity 使用 operator/token/access revision，pending localStorage key 按 operator 分区 | 单测覆盖 token 更新迟到回复、operator 切换后事实与 pending 隔离 | Chromium 经 ADMIN 路由守卫进入；命令、列表和恢复请求均携带当前员工 token，Console/pageerror 为 0 |
| 列表失败不会伪造空队列 | `loadReports` 只在当前 access/current revision 的合法分页响应后替换事实 | 单测先成功读取再返回 503，断言旧 report 保留 | 全量 E2E 和 V6.4 专项验证 320/390/1280px、axe serious/critical 0、根级横向溢出 0 |

受控 HTTP 服务用于稳定注入“事务已提交但响应丢失”，并记录浏览器实际请求。它不替代
真实 MySQL 事务和唯一约束。真实评价资格、并发提交、回复、举报审核、评分扣减、
RocketMQ 恢复和权限证据继续引用
[M8 商品评价](65-m8-product-reviews.md)与
[M0–M8 三层审查](69-m0-m8-pre-m9-three-layer-audit-20260728.md)。

## 7. 死代码与兼容测试清理

迁移后确认以下旧规则没有源码消费者并删除：

- `.review-governance-list`；
- `.review-governance-card`；
- `.review-governance-content`；
- `.review-governance-detail`；
- `.review-governance-form`。

旧 M8 浏览器用例原先依赖“平台回复已保存”“已隐藏并从评分汇总中移除”两段旧文案。
本批改为绑定 `.pj-status-notice--success` 的语义容器，再检查新的精确事实文案，避免
宽泛文本正则同时匹配处理中提示、字段标签和命令摘要。

## 8. 最终门禁

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 45 / 45 |
| UI primitives | 5 / 5 |
| Storefront | 171 / 171 |
| Admin | 60 / 60 |
| 前端单元/契约测试合计 | 287 / 287 |
| Review entity 专项 | 9 / 9 |
| 分层规则 | 26 / 26 |
| 分层文件 / 相对导入 | 141 / 253 |
| Playwright 全量 Mock E2E | 56 / 56 |
| V6.4 专项 Playwright | 15 / 15 |
| M8 Review 浏览器专项 | 2 / 2 |
| Catalog Review 后端专项 | 5 / 5 |
| 类型检查、生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 101.19 kB / gzip 14.33 kB
Storefront JS  356.90 kB / gzip 107.63 kB
Admin CSS      58.45 kB / gzip 8.47 kB
Admin JS       300.40 kB / gzip 89.71 kB
```

测试结束后 18090、18200、18201 均已释放。仅检测到 Codex 自身 Node 运行时，没有
PlainJournal Mock、Vite、Playwright、Catalog JVM 或 Java 服务残留。

## 9. 下一坐标

V6 仅剩管理端 Chat 与首页。本批不进入这两个页面，也不进入 V7。
