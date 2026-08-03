# M8 第六批：顾客端与客服端 Chat 会话工作区

> 状态：已完成  
> 完成日期：2026-07-24  
> 阶段边界：完成顾客与平台客服的文本会话工作区、结果未知恢复、实时投递和真实浏览器闭环；不开放附件控件，不冒充独立恶意文件扫描、Notification、GEO、评价、搜索、统计或整个 M8 完成

## 1. 本批目标

M8.1–M8.5 已经建立 Chat 的可靠事实、实时路由、附件授权、浏览器短期票据和
持久化消费失败治理，但普通用户仍无法从商城发起会话，客服也没有受领域权限约束的
工作台。M8.6 补齐两个真实客户端：

- 顾客端创建会话、查看会话、读取历史、发送文本和推进已读位置；
- 客服端查看待认领/已认领会话、认领后读取正文、回复和推进已读位置；
- 两端使用短期单次 WebSocket 票据连接真实 Chat 服务；
- 创建或发送响应丢失时不伪造失败，也不生成第二个业务事实；
- 页面刷新后以 MySQL 权威历史恢复，不把浏览器内存或 WebSocket 当最终事实。

## 2. 前端结构

共享 `@plain-journal/foundation` 新增 Chat 类型化客户端、游标历史模型、短期票据和
WebSocket 封装。顾客端与管理端分别保留独立状态：

```text
foundation/chat.ts
  -> REST 契约、游标分页、短期票据、WebSocket 生命周期

storefront-web
  -> entities/chat/model/customerChatStore.ts
  -> views/SupportChatView.vue

admin-web
  -> entities/admin-chat/model/adminChatStore.ts
  -> views/ChatWorkspaceView.vue
```

顾客端会话入口位于全局索引，不在所有页面常驻悬浮工具条。客服端沿用管理端角色
门禁，不新增可以绕过 `chat-service` 状态机或直接修改数据库的通用管理接口。

本批只开放文本消息。虽然 M8.3 后端附件闭环已经完成，但独立恶意文件扫描尚未实现，
因此界面明确不渲染上传、拖放或附件发送控件。

## 3. 结果未知与幂等恢复

### 3.1 创建会话

浏览器先生成稳定的 `clientConversationId`，请求响应丢失后使用原值重试。前端不能
重新生成键，否则会把一次用户操作放大为多个会话。

### 3.2 发送消息

每次发送使用稳定的 `clientMessageId`。响应丢失时，前端查询 MySQL 权威历史：

- 已存在同键消息：恢复为已发送；
- 尚未出现：保留可重试状态；
- 网络状态未知：不显示伪造成功。

### 3.3 WebSocket 生命周期

- REST 先换取短期单次握手票据，长期 JWT 不进入查询参数；
- socket 回调绑定到创建它的连接实例，旧连接关闭不能触发新连接的额外重连；
- 页面卸载、退出登录和环境切换都会停止刷新与重连；
- Origin 使用显式白名单，不接受任意浏览器来源；
- `prefers-reduced-motion`、键盘焦点和两套主题令牌继续生效。

## 4. 客服正文授权边界

客服可以看见待认领会话的有限队列元数据，但在认领前不能读取私聊正文。真实浏览器
门禁先以客服身份请求未认领会话历史并确认拒绝，再执行认领和正文读取。

该边界由后端授权裁决，不依赖前端隐藏元素。顾客、非成员和未认领客服都不能通过
直接调用 API 绕过。

## 5. 自动化与静态门禁

后端定向命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn -pl services/chat-service -am test
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
mvn -pl services/chat-service `
  com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs `
  '-Dspotbugs.effort=Max' `
  '-Dspotbugs.threshold=Low' `
  -DskipTests
```

前端门禁：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\frontend
pnpm check
pnpm audit --audit-level=high --registry=https://registry.npmjs.org/
```

结果：

- `platform-common`：14 tests；
- `chat-service`：38 tests；
- 全后端 12 个 Reactor 模块 PMD：0 违规；
- Chat SpotBugs 低阈值专项：37 条诊断，其中 Priority 1 为 0、Priority 2 为 28、
  Priority 3 为 9；
- 前端：83 个 Vitest，其中 Foundation 31、Storefront 50、Admin 2；
- Playwright：4 个 E2E；
- 两端类型检查和生产构建通过；
- 官方 npm registry 审计无已知漏洞。

最近一次全量后端 `mvn clean verify` 仍是 76 份 Surefire 报告、274 个测试。上述
M8.6 定向结果不能改写为已经重新执行全量后端门禁。

## 6. 真实浏览器与中间件证据

命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
./tools/verify-m8-chat-frontend-workspace.ps1 `
  -SkipPackage `
  -SkipFrontendBuild
```

权威证据：

```text
backend/.run/m8-chat-frontend-final-20260724/verification.json
```

真实拓扑包括 Identity、Chat、Gateway、顾客端 Vite、管理端 Vite、MySQL、Redis、
Nacos、RocketMQ 和 headless Chrome。验证结果：

- 创建响应被故意丢弃后，第二次请求复用原 `clientConversationId`；
- 发送响应被故意丢弃后，从 MySQL 权威历史恢复；
- 客服认领前无法读取私聊正文；
- 顾客和客服 WebSocket 均连接成功；
- 客服回复无需刷新即可到达顾客端；
- 刷新后恢复完整历史；
- 最终恰好 1 个会话、2 个成员、2 条消息和 2 个发送方；
- 两条 Outbox 均为 `PUBLISHED`；
- 私聊正文进入 Outbox 的数量为 0；
- 附件事实为 0，页面没有开放附件控件；
- `pageerror`、非预期 HTTP、网络和控制台错误均为 0。

## 7. RocketMQ 历史消息与清理边界

早期 M8.6 验证每次生成新的 Dispatcher consumer group，导致 RocketMQ 保留期内
的 M8.5 毒消息被多个验证组重新读取，并留下三条可归属的 `consumer_failure`。
数据库记录、消息 ID、时间戳和三个运行目录日志完成交叉核对后，只删除了这三条
精确记录。

历史修复阶段曾使用固定专用组：

```text
ecommerce-chat-dispatcher-m8-frontend-workspace-verifier
```

并把以下事实写入 `verification.json`：

- 运行前该验证组清理的失败记录数；
- 停止应用前观察到的验证组失败记录数；
- 与当前会话消息关联的失败记录数；
- 清理后的失败记录数。

固定组连续两次真实复验均得到：

```json
{
  "preexistingRowsRemoved": 0,
  "observedBeforeCleanup": 0,
  "currentConversationRows": 0,
  "cleanup": {
    "consumerFailureRows": 0
  }
}
```

第二次复验结束后，Identity 用户、Chat 业务数据、失败台账、Redis Key、应用端口、
受管 JVM 和 Vite 进程均为 0。

### 7.1 2026-07-25 Broker 元数据零残留加固

固定消费组避免了同一验证器反复读取历史消息，但会把验证专用消费组永久留在
RocketMQ Broker。整体审查已将脚本调整为运行级 Dispatcher 与节点投递消费组，
并保留更严格的业务隔离：

- 当前会话是否产生消费失败仍按 `messageId` 精确核对；
- Topic 保留期内旧事件产生的失败行只按本次消费组归属，不混入业务断言；
- 应用停止后删除本次两个消费组，最多重试三次并二次反查；
- `finally` 执行幂等兜底清理；
- 新证据增加 `cleanup.residualRocketMqConsumerGroups`，非空即失败。

因此当前门禁不是“Broker 中保留一个固定验证组”，而是“每次运行有独立身份，
业务断言精确归属，运行结束后 Broker 元数据为零残留”。

## 8. M8.6 结论与后续边界

M8.6 已把 Chat 的可靠后端能力接入顾客和客服的真实浏览器工作区，并保留了与后端
一致的授权、幂等、结果未知和最终事实边界。该批完成时 M8.1–M8.6 已完成，但整个
M8 尚未完成。

后续继续按独立闭环实施：

- Notification 可靠投递；
- 物流 GEO；
- 商品评价；
- 搜索索引、重建与事实核对；
- 运营统计读模型。

上述是本批交付时的历史边界。2026-07-25 当前状态以
[M8 全面审查、回归与毕业收口](68-m8-full-audit-and-graduation-20260725.md)
为准：M8.1–M8.12 已完成并关闭。

后续 M8.7 已完成附件隔离、真实 ClamAV 扫描、有限重试与管理员审计重扫，但前端
附件控件仍保持关闭。详见
[M8 第七批：聊天附件隔离、恶意文件扫描与审计恢复](62-m8-chat-malware-scan-and-quarantine.md)。

## 9. 2026-08-03 管理端视觉迁移后的边界加固

M8.6 的真实中间件证据没有被视觉重构替代。V6.4.3 管理端 Chat 迁移进一步把客服端
从旧顶层 store 收入独立 entity：

```text
staff-session
  -> 页面只组合 authorized / operatorId / accessToken
  -> admin-chat entity 创建独立 workspace 代次
  -> Foundation Chat controller
  -> Chat REST / WebSocket ticket
```

新增约束：

- pending 客服回复按 `operatorId` 使用独立 localStorage key，员工切换后另一员工不能
  看到正文、客户端消息键或执行重试；
- 同一员工 token 轮换时也创建新 workspace，迟到 REST 和旧 WebSocket 回调只能修改
  已废弃状态；
- 队列 GET 只展示摘要，未认领时不请求消息历史；
- 认领 5xx 后必须由同一会话的客服成员事实确认，确认前正文继续隐藏；
- 关闭 5xx 只有在单条会话 GET 返回 `CLOSED` 后才显示完成；
- 未确认回复存在时禁止关闭会话，避免把“请求未到达”锁死为无法原键恢复；
- 会话、消息、附件、已读回执和票据增加运行时契约校验，64 位业务 ID 只接受十进制
  字符串；
- 页面仍不开放附件上传或下载。已经绑定到消息的附件只展示元数据和隔离扫描边界。

代码、自动化和 Chromium 三层证据见
[V6.4.3 Chat 客服工作区](100-frontend-visual-v6-4-3-chat-20260803.md)。
