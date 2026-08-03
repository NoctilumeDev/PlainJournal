# 前端低耦合分层第五批：购物袋、账户购物车与合并边界

> 完成日期：2026-07-30  
> 范围：设备游客袋、Trade 账户购物车、登录后合并、所有者切换、结果未知和购物袋页面  
> 边界：不进入 M9，不改变 Trade 购物车协议，不拆 Checkout 的价格、库存、优惠或下单状态机

## 1. 结论

第五批把“购物袋”从一个页面里混合的本地状态、会话状态和 Trade 远端事实拆成三块：

```text
entities/guest-bag             当前设备事实
          ↓
features/customer-session      登录与跨 Identity/Trade 合并编排
          ↓
pages/bag/BagPage              页面组合与结果呈现
          ↑
entities/account-cart          Trade 账户事实
```

游客袋和账户购物车不是同一个实体：

- 游客袋属于当前浏览器设备，服务端未确认合并前不能删除；
- 账户购物车属于 Trade 和当前 JWT 所有者，MySQL 是最终事实；
- 登录后合并是跨 Identity/Trade 的工作流，继续由 session feature 编排；
- page 只组合公开入口，不持有另一套购物车事实；
- Checkout 继续显式读取账户购物车，但展示快照不是价格、库存或优惠承诺，提交前仍
  重新裁决。

本批没有新增后端接口。`PUT /cart/items/{skuId}`、`DELETE /cart/items/{skuId}`、
`GET /cart/items` 和 `POST /cart/guest-merge` 均为既有 Trade 契约；前端 foundation
只是补齐此前未公开使用的 PUT/DELETE 客户端方法。

## 2. 代码结构与依赖方向

```text
storefront-web/src/
├── entities/
│   ├── account-cart/
│   │   ├── model/accountCartStore.ts
│   │   ├── model/accountCartStore.test.ts
│   │   ├── ui/AccountCartItemRow.vue
│   │   └── index.ts
│   └── guest-bag/
│       ├── model/guestBag.ts
│       ├── model/guestBag.test.ts
│       ├── ui/GuestBagItemRow.vue
│       └── index.ts
├── features/customer-session/
├── pages/bag/BagPage.vue
└── app/AppHeader.vue
```

- 旧 `stores/accountCart.ts` 与 `views/BagView.vue` 已移除；
- 购物袋路由只指向 `pages/bag/BagPage.vue`；
- 行项目交互分别由两个 entity 的 UI 组件拥有；
- `entities/account-cart` 不读取 session store，只接受显式
  `AccountCartAccessContext`；
- AppHeader、BagPage 和 Checkout 都通过 account-cart 公开 `index.ts` 使用事实；
- 购物袋旧全局样式已删除，page/entity 使用各自 scoped style。

最终分层门禁为 9 条规则，扫描 45 个分层文件和 177 条相对导入。新增规则同时阻止：

- account-cart entity 反向依赖 customer-session；
- page 或旧 store 深入 account-cart 内部文件；
- account-cart 外部消费者绕过公开入口。

## 3. Trade 事务与前端结果边界

### 3.1 普通账户写入

Trade 的 PUT/DELETE 都按 JWT 用户取得 `userId`，在本地事务内先创建并锁定
`cart_user_lock` 的用户行，再读取或修改该用户的购物车。PUT 是“设置目标数量与
selected 状态”，不是基于旧值做前端增量；DELETE 同时带 `user_id + sku_id` 条件。

当前 PUT/DELETE 协议没有请求幂等键。它们的目标状态语义使同一请求重复执行通常
收敛到相同状态，但前端不能把网络异常解释为已成功，也不能在响应丢失后无界自动
重放。因此 account-cart 采用：

1. 同一时刻只允许一个购物车写入；
2. 8 秒超时、网络错误、非法响应、5xx 或响应身份不匹配进入 `unknown`；
3. 未知态不乐观修改已确认数组；
4. 用户必须先重新读取 Trade 事实，再决定下一动作；
5. 所有写入成功响应都核对 productId/skuId，再写入当前 owner 状态。

前端串行写与后端按用户行锁串行化的吞吐边界一致：同一用户购物车不追求并行写入
吞吐，跨用户仍可并行。

### 3.2 游客袋合并

游客袋合并具有真正的幂等协议：

- 浏览器生成 `guest-merge:{uuid}` 稳定键；
- 相同 pending 合并保留同一 key 和同一 items 快照；
- Trade 对规范化请求计算 SHA-256；
- `cart_merge_request` 以 `user_id + merge_key` 唯一；
- 用户购物车锁、合并请求与购物车修改在同一本地事务；
- 同键同请求只应用一次，同键不同请求返回幂等冲突。

服务端响应丢失时，前端保留游客商品和 pending 快照，显示“结果暂时未知”；用户用
原键确认后，只有 Trade 明确返回成功，才按原合并快照从设备袋扣除。新的加购不会被
旧 pending 结果误删。

### 3.3 owner、token 与迟到响应

account-cart 保存 owner、token、访问代次、读取代次和写入代次：

- owner 改变时立即清空旧数组、错误和反馈；
- 同 owner 的 token 更新会使旧请求失效；
- A 的慢响应在切到 B 后到达，只会抛出 `AccountCartAccessChangedError`；
- 重复 GET 合并为一个活动 Promise；
- 合并成功或结果未知后，AppHeader/BagPage 强制重新读取，旧快照不能覆盖；
- 写入过程中切换账户时，旧结果不会显示为新账户成功。

session 合并也捕获 owner、token 和会话代次。同 owner 的并发合并会合并为同一个
Promise；迟到响应不能清理另一 owner 的设备袋。

## 4. 设备袋数据治理

游客袋读取 localStorage 时不盲信历史数据：

- 非数组、非法业务 ID、空标题、非法金额和非法数量会被拒绝或修复；
- 相同 SKU 持久化行会去重并规范化；
- pending merge 必须同时具有合法 key、owner、items 和请求快照；
- `NaN`、`Infinity` 或越界数量不会覆盖最后一个有效值；
- 页面数量上限为 999，设备袋 SKU 行上限为 100；
- 业务 ID 全程保持十进制字符串，不经过 JavaScript Number。

本批真实商品、SKU 和两个用户 ID 均大于 `Number.MAX_SAFE_INTEGER`，浏览器、Gateway
和 MySQL 全链保持精确字符串/Long 对应。

## 5. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实运行证据 |
| --- | --- | --- | --- |
| 账户所有者隔离 | account-cart 的 owner/token/revision；Trade 所有查询和写入均取 JWT userId | store 单测模拟 A 慢响应晚于 B；Mock E2E 在两个 token 间切换 | 真实 Gateway 中 A 初始数量 2、B 为空；B 合并、修改、删除后，重新登录 A 仍严格为 2 |
| 迟到响应不能串户 | owner 变化立即清数组，旧访问代次不能提交 | 单测覆盖旧 GET、旧写入和 token 更新；分层规则禁止 entity 隐式读取 session | 两个真实大整数用户通过同一页面切换，页面和 MySQL 均未出现跨 owner 行 |
| 合并响应丢失 | session 保留稳定 key/body/pending；Trade 用户锁、请求哈希和唯一键同事务 | session/guest-bag 单测；Mock E2E 精确断言两次请求 key/body 相同 | 真实代理先取得 Trade HTTP 200 再丢弃响应；页面保持游客 pending，原键重试后数量仍为 3 而非 6 |
| PUT 响应丢失 | account-cart 将网络/超时/非法响应/5xx/身份不匹配分类为 unknown | store 单测与 Mock E2E；未知态先读后写 | 真实 PUT 已在 Trade 提交为 4、浏览器丢失响应后仍显示已确认 3；重新读取后才显示 4 |
| 写入时序 | 前端只允许一个 mutation；Trade 使用每用户 `cart_user_lock` | store 单测覆盖 busy、强制读取和响应逆序 | 真实浏览器依次修改数量、selected 和删除，全部由 Trade 200 确认，没有响应逆序覆盖 |
| 删除与二次确认 | 行组件保存局部确认态，DELETE 成功才移除事实 | Storefront 单测和 Mock E2E 覆盖保留/确认/失败 | 内置浏览器实际取消选择、恢复选择、打开确认组并删除，最终 B 为 0 |
| 设备袋损坏与旧 pending | guest-bag 在存取边界解析、去重、修复和限制 | guest-bag 单测覆盖畸形 JSON、重复 SKU、NaN/Infinity 和非法 pending | 真实浏览器两次加购显示数量 2，登录成功后设备袋和 pending 均被正确清除 |
| Checkout 不把快照冒充裁决 | Checkout 仅通过 account-cart 公开入口读展示事实，仍调用 Catalog/Inventory/Marketing | 既有 checkout 单测完整通过 | 真实购物袋文案明确说明 Trade 快照；真实 M4 权威结算继续以三域当前事实裁决 |
| 分层权限 | account-cart/guest-bag 无 session 反向依赖，BagPage 只经公开入口组合 | 9 条规则扫描 45 文件、177 导入 | Vite 桌面和 390×844 移动页面均加载新 page/entity 路径，控制台错误为 0 |

## 6. 真实链路与浏览器证据

### 6.1 最小真实运行集

遵循 Y7000P 单机边界，只串行启动：

```text
MySQL + Redis + Nacos
Identity + Catalog + Trade + Gateway
Storefront Vite
```

RocketMQ 没有启动，因为本批购物车 PUT/DELETE/guest-merge 都是 Trade 本地事务，
没有 Outbox 推进要求。省略无关中间件不等于 Mock：用户、商品、购物车、用户锁和
合并请求均落在真实 MySQL，认证和路由经过真实 Identity、Nacos 与 Gateway。

运行准备还验证了两个配置边界：

- Nacos 远端配置会覆盖只设置 `TRADE_*` 占位环境变量的关闭开关，真实验证必须使用
  直接 `ECOMMERCE_TRADE_*` 属性关闭本批无关调度；
- 旧 Trade worker lease 尚存时，新实例被多实例保护正确拒绝；本批使用独立
  distributed ID namespace 后才启动，未绕过租约保护。

### 6.2 自动化真实浏览器

专用 `playwright.real-cart.config.ts` 直连 Gateway，1 个用例通过，覆盖：

1. A 读取数量 2；
2. B 读取为空；
3. 游客加购 3；
4. guest-merge 服务端 200 后丢失浏览器响应；
5. 原 key/body 重试，B 仍为 3；
6. PUT 数量 4 服务端 200 后丢失浏览器响应；
7. 页面先保持 3，重新读取后为 4；
8. selected 关闭/恢复；
9. B 删除；
10. A 再次读取仍为 2。

浏览器捕获两条受控 `net::ERR_FAILED`，分别对应主动丢弃的 merge 和 PUT；除此之外
console error、page error 为 0，所有已收到的购物车 HTTP 响应均为 200，
axe serious/critical 为 0。

### 6.3 内置浏览器人工复核

内置浏览器独立执行正常用户路径，而不是复述自动化断言：

- 打开真实商品页并加购两次；
- 游客袋显示 2 件和 ¥378.00；
- 登录 B 后真实合并为账户数量 2；
- selected 关闭后小计归零，恢复后回到 ¥378.00；
- 二次确认删除后 B 购物车为空；
- 退出 B、登录 A，A 仍显示数量 2；
- 桌面 1440×900 和移动 390×844 均无水平溢出或遮挡；
- 两个视口 console error 均为 0。

内置浏览器只提供页面、交互、响应式和控制台证据；HTTP method/status、响应丢失和
同键请求由同一次真实 Playwright request/response 监听取证，不能用日志或 DOM
文本冒充 F12 网络证据。

### 6.4 MySQL 最终事实与清理

浏览器完成后、清理前重新挂载原 PlainJournal MySQL 数据卷并直接查询：

```text
owner A cart       quantity=2, selected=1
owner B cart       0 rows
owner B merge      2 rows
```

两条 merge 分别使用不同稳定键，对应自动化响应丢失旅程和内置浏览器正常旅程；
这证明两次旅程都只合并一次，没有把 B 的删除影响到 A。随后按精确用户、商品、
分类、品牌和 distributed ID namespace 清理，复查结果为：

```text
identity residual  0
catalog residual   0
trade residual     0
lease residual     0
context file       absent
```

## 7. 最终自动化门禁

重启后为避免动态端口叠加，门禁按阶段串行执行：

```text
分层规则       9 passed
Foundation    42 Vitest
Storefront    77 Vitest
Admin         12 Vitest
合计          131 Vitest
Playwright    12 Mock E2E
Real Cart      1 Gateway E2E
typecheck     Foundation / Storefront / Admin passed
build         Storefront / Admin passed
axe           serious / critical = 0
```

生产构建：

```text
Storefront CSS 53.04 kB / gzip 8.63 kB
Storefront JS  281.74 kB / gzip 86.84 kB
Admin CSS      19.43 kB / gzip 3.91 kB
Admin JS      192.00 kB / gzip 61.89 kB
```

Mock E2E 严格使用 1 worker。运行前动态端口快照为 TCP 116 个唯一端口、
73 个 `TIME_WAIT`、UDP 20 个唯一端口，近 15 分钟新增 `4231/4266` 为 0。

## 8. 运行边界与故障教训

本批真实链路完成后，Windows 又出现 TCP `4231`、UDP `4266` 和 Codex/代理连接
故障；同时现场抓到 `ChatGPT/Codex -> PowerShell/pwsh -> conhost` 的闪窗父子
关系。最终结论是动态端口耗尽与控制台闪窗两个问题叠加，不把闪窗误认成
PlainJournal 服务，也不据此修改路由、网卡、Clash 或 Docker 数据。

后续前端切片固定采用：

- 单个 Codex 重型任务；
- 测试、浏览器和中间件互斥串行；
- 每批前后检查动态端口余量与最近 `4231/4266`；
- 真实链路只启动机制必需组件；
- Docker Desktop 启动后立即检查 restart policy 自动恢复的容器；本批清理阶段发现
  MySQL、Redis、Nacos 会随引擎恢复，随即串行停止无关的 Nacos/Redis，只保留
  MySQL，完成后再停止 MySQL 和 Docker Desktop；
- 不为宿主机已有 MySQL 申请管理员权限或强杀进程；PlainJournal MySQL 使用既有
  `127.0.0.1:13306` 映射，机器级 MySQL 始终保持原状态；
- 重启或连接回收后不根据当前 PID 反推历史责任进程。

完整时间线与现场取证命令见
[本地开发网络基线](07-local-development-network.md)。

## 9. 下一边界

本批完成后，游客袋、账户购物车和会话合并已形成可独立复用的积木。下一前端切片
可继续选择 Checkout 或订单工作区，但必须先审查其跨 Catalog、Inventory、
Marketing、Trade 和 Payment 的同步边界，不为了目录整齐一次拆开全部交易状态机。

M9 继续冻结。
