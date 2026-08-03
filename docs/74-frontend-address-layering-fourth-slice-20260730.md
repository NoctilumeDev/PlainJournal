# 前端低耦合分层第四批：地址实体、所有者隔离与账户页面

> 完成日期：2026-07-30  
> 范围：Identity 地址事实、地址管理页面、Checkout 地址读取入口、所有者切换竞态和前端运行环境  
> 边界：不进入 M9，不改变 Identity 地址协议、Checkout 价格/库存/优惠裁决、订单、支付或售后状态机

## 1. 结论

第四批没有为目录对称虚构一个 `address-management` feature。地址管理目前只有一个账户
页面，真正可复用的是地址事实与 Identity API 状态，因此采用“地址 entity + page
编排”的最小结构：

```text
foundation identity contract
          ↓
entities/address
          ↓
pages/account/AddressManagementPage
          ↑
features/customer-session
```

page 同时组合下层地址 entity 和现有会话 feature；地址 entity 不向上读取 Pinia
会话，也不知道游客袋或页面交互。Checkout 只改为通过 `entities/address/index.ts`
读取相同地址事实，并显式提供当前账户访问上下文；其价格、库存、优惠、60 秒权威快照、
幂等订单和结果未知恢复逻辑没有改动。

## 2. 代码边界

```text
storefront-web/src/
├── entities/address/
│   ├── model/addressStore.ts
│   ├── model/addressStore.test.ts
│   └── index.ts
├── features/customer-session/
├── pages/account/
│   ├── AccountPage.vue
│   └── AddressManagementPage.vue
└── app/router.ts
```

- 旧 `stores/addresses.ts`、`stores/addresses.test.ts` 和
  `views/AddressManagementView.vue` 已移除，旧路径引用为 0。
- 地址页面样式迁入页面 scoped style；全局 `storefront.css` 不再拥有
  `.address-page`、`.address-layout`、`.address-row` 或 `.address-form`。
- 外部消费者只能通过 `entities/address/index.ts` 使用地址状态；新增分层规则同时
  阻止地址 entity 反向依赖 `features/customer-session`，也阻止 page 深入 entity
  内部文件。
- `AddressAccessContext` 显式携带 `authenticated`、`ownerId` 和当前 access token。
  token 仍由会话 feature 持有；entity 只在单次 Identity 请求中使用调用方给出的
  授权上下文。

## 3. 所有者、时序与结果边界

原地址 store 隐式读取全局会话，并在异步 GET 返回后直接覆盖数组。如果账户 A 的
慢请求在切换到账户 B 后才返回，A 的地址可能短暂写进 B 的页面。第四批增加访问代次：

1. ownerId 改变时立即清空旧地址、错误、loading 和 saving 状态；
2. access token 改变时保留同一 owner 的已确认地址，但使旧请求代次失效；
3. GET 只有在 owner、token、访问代次和请求代次仍一致时才能提交结果；
4. 旧请求完成后抛出 `AddressAccessChangedError`，不能覆盖新账户事实；
5. 页面 owner 改变时同步清空编辑表单、删除确认和成功反馈，避免泄露上一账户的
   草稿状态。

写入也区分“业务写入失败”和“写入已确认、列表刷新失败”：

- Identity create/update/default/delete 未确认时保留表单或删除确认，不宣称成功；
- Identity 已确认写入而随后 GET 失败时，写入 Promise 仍返回已确认结果，同时显示
  “已确认但列表未能重新读取，勿重复提交”，避免新增地址被用户重复创建；
- 写入过程中 owner 或 token 改变时，旧页面不展示成功反馈，当前账户也不接管旧结果。

## 4. 地址版本的真实含义

`Address.version` 是 Identity 返回的服务端事实，但 `AddressInput` 和 PUT 请求没有
`version` 或 `expectedVersion`。后端当前通过用户账户行锁串行化同一用户的地址写入，
按 `user_id + address_id` 校验所有权，并在持锁读取后执行带 MyBatis Plus
`@Version` 的更新。

因此本批明确：

- 前端保持并展示/传递服务端返回的版本事实，不丢失它；
- 更新请求不得自行增加一个后端未声明的 version 字段；
- 当前协议不提供客户端乐观并发编辑冲突提示，不能把内部版本字段宣传成
  “前端乐观锁”；
- 若未来要做跨设备编辑冲突，必须先扩展后端命令契约和 409/版本冲突语义，再由前端
  提交 expectedVersion。

## 5. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实运行 |
| --- | --- | --- | --- |
| 所有者隔离 | 地址 store 使用 owner/token/access revision，page 切换 owner 时清表单和确认 | 单测让 A 的 GET 延迟、B 的 GET 先完成，随后确认 A 结果被拒绝；E2E 在两个 customer token 间切换 | 内置浏览器先读取 `Test Customer`，退出后登录第二账户只读取 `Second Customer`，上一账户地址为 0 |
| 默认地址唯一与删除回补 | setDefault/delete 只接受 Identity 已确认结果，再重新读取事实 | E2E 新增、设默认、取消删除、确认删除，最终原地址重新成为唯一默认 | 桌面浏览器实际观察默认标签从原地址移到新地址，删除默认地址后回到原地址 |
| 地址版本协议 | foundation 的 update body 只有 `AddressInput`，entity 不注入 version | 单测断言 PUT body 无 version，随后 GET 返回 version 4 并原样保存 | 浏览器 E2E 捕获真实 PUT request body，无 version；全部地址 HTTP 响应为 200 |
| 写入已确认、刷新失败 | mutation 将服务写入与后续 GET 分成两个完成点 | 单测模拟 POST 200、GET 503，create 返回已确认实体且错误明确提示勿重复提交 | 正常浏览器旅程只有服务确认后显示成功，控制台没有页面 warn/error |
| 删除确认 | page 保存 `pendingDeleteId`，失败不关闭确认 | Playwright 先点“保留地址”确认数据仍在，再确认删除 | 内置浏览器重复同一路径，确认组可取消，最终删除后默认地址回补 |
| 分层权限 | entity 无 session import；page 通过两个公开入口组合 | 8 条依赖规则扫描 39 个分层文件和 173 条相对导入 | Vite 页面资源清单观察到 page、entity public entry、address store 和 session public entry 进入真实运行图 |

## 6. 自动化门禁

最终 `pnpm check` 一次通过：

```text
分层规则       8 passed
Foundation    41 Vitest
Storefront    63 Vitest
Admin         12 Vitest
合计          116 Vitest
Playwright    11 E2E
typecheck     Foundation / Storefront / Admin passed
build         Storefront / Admin passed
axe           serious / critical = 0
```

Storefront 最终构建：

```text
CSS 49.82 kB / gzip 8.23 kB
JS  271.80 kB / gzip 84.43 kB
```

新增浏览器 E2E 记录至少 10 个地址请求，覆盖 GET、POST、PUT、set-default POST 和
DELETE，状态均为 200；PUT 请求体断言无伪造 version。受控 Mock API 为第二 customer
提供独立地址集合，update/default/delete 同步模拟服务端 version 递增和默认地址回补。
它只证明前端契约、交互与所有者呈现，不替代 Identity 的真实 MySQL/JWT/RBAC 证据。

## 7. 内置浏览器与响应式复核

桌面 1280×720 运行结果：

- 页面宽约 1232.67 px；
- 地址两列约为 671.98 / 496.69 px；
- 表单保持 sticky；
- 新增、设默认、修改、删除取消、删除确认与默认回补全部完成。

请求视口 375×844 时，浏览器内部 viewport 为 375×844、可用 client 为 360×844，
页面宽 340 px；地址布局为单列 340 px，表单变为 static，地址操作变为横排，
`scrollWidth == clientWidth == 360`，水平溢出为 0。

页面控制台 warning/error 为 0。资源清单共观察到 100 项，其中包括：

- `pages/account/AddressManagementPage.vue` 及 scoped style；
- `entities/address/index.ts` 和 `model/addressStore.ts`；
- `features/customer-session/index.ts` 和 `model/session.ts`。

内置浏览器接口不提供可直接读取的 DevTools Network method/body 面板，因此 HTTP
方法、状态和 PUT body 由同一次真实 Chrome Playwright E2E 的 request/response
监听器取证；内置浏览器独立负责可见页面、交互、响应式、控制台和运行模块复核。
两者不能互相冒充。

浏览器控制层在本地页面正常运行期间两次报告访问 `ab.chatgpt.com` 的 10 秒超时；
同一时段近 15 分钟没有新的 TCP `4231`，本地 Storefront/Mock API 正常。这是
Codex 外部代理链路证据，不是 PlainJournal 页面错误，已按
[本地开发网络基线](07-local-development-network.md)与历史端口耗尽分层记录。

## 8. Node 与机器边界

本机存在两个不同入口：

```text
D:\Node.js\node.exe          = 18.20.8
D:\Node.js\current\node.exe  = 24.14.0
```

当前 PATH 的 `corepack` 正确命中 `D:\Node.js\current`。一次隐藏启动若错误硬编码根目录
Node 18，Vite 会因缺少 `node:util.styleText` 立即失败；改用 current 后正常启动。
项目脚本和人工验证必须使用 PATH/current，不修改或混用两个机器级入口。

由于本机曾反复出现 TCP `4231`，全量 Playwright 门禁与内置浏览器人工复核采用串行
执行；人工复核只隐藏启动一个 Storefront 和一个 Mock API，不启动 Docker、Java 或
Admin。本节人工复核时近 15 分钟没有新的 `4231/4266`，所以该次
`ab.chatgpt.com` 超时不能归因给端口耗尽。当天稍后的独立故障窗口实际记录了
06:18:50 TCP `4231`、08:14:55 UDP `4266`、09:09–09:17 Codex 连续连接失败，
并现场抓到 `ChatGPT/Codex -> PowerShell/pwsh -> conhost` 的闪窗父子关系。
两个结论不冲突：前一次是孤立上游超时，后一次是端口耗尽与控制台闪窗两个问题
叠加。完整时间线和取证边界见
[本地开发网络基线](07-local-development-network.md)。

最终收口时 18000、18090、18200、18201、5173 和 18101–18110 监听为 0，
PlainJournal Java/Node 进程为 0，近 15 分钟新增 `4231/4266` 为 0；人工复核产生的
临时启动日志目录已删除。Docker 中 PlainJournal 七个核心容器和一次性初始化容器
保持原 stopped/exited 状态，本批没有启动、重启或修改容器，也不根据既有退出码
推断历史停止原因。

## 9. 下一最小风险切片

下一批建议处理购物袋页面和账户购物车边界：

1. 区分设备游客袋 entity、Trade 账户购物车事实和页面组合；
2. 保留稳定合并键、跨 owner 阻断、结果未知与按提交数量扣减语义；
3. 不提前拆 Checkout，也不改变提交订单、库存、优惠或支付闭环；
4. 继续使用代码、自动化和真实浏览器三层验证。

M9 继续冻结。
