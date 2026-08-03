# 素简记前端视觉重构总计划

> 制定日期：2026-08-01  
> 当前状态：V1–V7.4 已全部完成；Apache-2.0 `v1.0.0` 开源发布基线已验证  
> 产品边界：当前仓库止于自营 B2C v1.0，不进入多商户与 Go 平台化改造  
> 实施原则：先冻结业务事实，再建立统一视觉语法，最后按真实用户旅程迁移

## 1. 决策与目标

本轮不是逐页换色、套卡片或重做一个脱离业务的静态样板，而是在现有低耦合结构上
建立统一视觉语言。代码可以按所有者域拆成积木，顾客看到的仍应是一条连续旅程：

```text
我在看什么
  -> 当前事实是什么
  -> 我现在能做什么
  -> 结果未知或失败时怎样恢复
```

“青荷”为默认气候，“素白”为备用气候；两者使用同一排版、布局、组件和语义状态
系统，只替换品牌与表面语义令牌。目标是清、净、润、轻、克制的现代商城，不是荷花
主题、国风页面或广告落地页。

## 2. 不可变边界

视觉重构不得顺手改变以下事实：

- API 路径、DTO、状态机、权限、所有者隔离和跨服务边界；
- 下单、支付、确认收货、评价、寄回、退款等稳定幂等键；
- 响应丢失后的原键查询恢复和结果未知状态；
- Payment、Trade、Inventory、Fulfillment、Marketing、Catalog 各自的裁决权；
- `PROCESSING`、`NEEDS_ATTENTION`、失败、退款成功等语义；
- 无权限时的 401/403/404 处理和失败关闭规则；
- 真实 URL、刷新恢复、浏览器历史和深链接契约。

如果某个视觉方案要求合并动作、隐藏风险状态、提前显示成功或复制领域状态机，该
方案直接淘汰。任何确需改变交互语义的需求必须退出视觉批次，另立业务设计与三层
验证切片。

## 3. 当前只读资产基线

本计划建立前已只读检查当前源码和既有浏览器截图，没有修改页面样式。

### 3.1 路由与样式规模

| 项目 | 当前事实 |
| --- | ---: |
| 顾客端路由 | 19 |
| 管理端路由 | 13 |
| Vue 文件 | 50 |
| 带 `<style>` 的 Vue 文件 | 18 |
| scoped style | 17 |
| 行内 `style=` 文件 | 0 |
| 独立 CSS 文件 / 总行数 | 6 / 2690 |
| `storefront.css` | 1490 行 |
| `admin.css` | 960 行 |
| 顾客端 / 管理端全局类选择器起点 | 291 / 160 |
| `var(--pj-*)` 使用 | 907 |
| 令牌外硬编码颜色 | 0 |
| 媒体查询 | 35 处、8 种表达 |
| box-shadow / border-radius 声明 | 4 / 11 |

颜色已经集中到 `@plain-journal/design-system/tokens.css`，这是可保留的正确基础。主要
债务不是“到处写死颜色”，而是全局 CSS 仍同时持有商品、交易、评价、售后、账户和
支持页面样式；正式 primitives 尚未建立，页面和领域组件对 `.primary-action`、
`.payment-state`、`.checkout-section` 等全局类存在隐式依赖。

当前响应式主线以 32/48/64rem 为主，但仍存在 36rem、40rem、760px 和 900px 四种
局部断点。重构时要收敛为命名断点语义，不能机械替换数值后破坏真实布局。

### 3.2 图片资产

| 资产 | 尺寸 | 当前体积 |
| --- | ---: | ---: |
| `mist-blue-notebook.png` | 1122×1402 | 2329.3 KiB |
| `canvas-commuter-tote.png` | 1122×1402 | 1952.3 KiB |
| `qinghe-parcel-route.png` | 1672×941 | 1447.3 KiB |

三张图片合计约 5.59 MiB。前两张商品图比例一致，适合作为商品媒体基线；但必须在
不降低观感的前提下评估 WebP/AVIF、响应式尺寸和加载策略。履约插图可以保留，但只
承担事实解释，不能扩散为装饰性广告图。

### 3.3 已确认的视觉问题

既有商品、订单和售后浏览器截图表明：

- 商品详情首屏方向基本成立，但纵向留白过大，后段评价又退回明显卡片阵列；
- 页面同时出现 eyebrow、领域名、状态码、标题、说明和边界说明，信息层级过多；
- 订单详情把 Trade、Payment、Fulfillment 的技术所有权直接暴露成视觉分段；
- 多个刷新、继续、返回和治理说明竞争注意力，主要行动不够稳定；
- 状态标签、提示面和摘要面使用方式不统一，复杂状态容易重新堆成后台面板；
- 中英文夹杂和技术性文案增强了“测试工作台”而不是成品商城的感觉；
- 大面积浅底与细线虽然克制，但缺少明确节奏，长页面容易显得稀散；
- 当前 primitives 只有共享异步状态雏形，按钮、字段、状态、表面和行动组仍主要依赖
  全局类，后续很容易再次产生页面级覆盖。

这些是 V1 审计的初始事实，不等于已经确定具体视觉解法。

## 4. 视觉语言

### 4.1 气质

```text
清：信息层级明确，没有无意义装饰
净：商品、事实和行动之间留有可呼吸空间
润：青荷不是冷科技色，表面之间有柔和过渡
轻：边界弱、阴影少、容器不过度封闭
克制：不靠口号、徽章和大按钮制造重要感
```

禁止荷花背景、荷叶纹样、水波动画、花瓣按钮、古典字体、印章、书法、毛玻璃、黑金
奢侈品风格和大规模动效。参考自然图像时只提取色彩与空气关系。

### 4.2 文案预算

每个页面区域原则上只保留：一个标题、一段必要说明、一个主要行动。eyebrow 只用于
真正需要建立语境的区域，不能成为每个 section 的固定装饰。面向顾客的主文案优先
解释事实与下一步；服务名、内部状态码和技术边界降到辅助层，必要时按需展开。

“价格以服务端为准”“不会伪造成功”等信任说明仍保留，但不得在同一页面重复广播。

## 5. 令牌体系

令牌分为两层，页面禁止直接使用色值：

```text
基础令牌
  palette / typography / spacing / radius / shadow / duration / breakpoint
        ↓
语义令牌
  canvas / surface / text / line / action / focus / media / status
        ↓
primitives 与领域组件
```

基础令牌描述可选材料，例如 `lotus-*`、`paper-*`、`mist-*`；语义令牌描述用途，例如
`surface-page`、`text-secondary`、`action-primary`。页面与业务组件只能消费语义
令牌，不能消费基础色阶或十六进制值。

以下语义跨主题保持认知一致，只允许经过对比度验证的细微适配：

- success；
- warning；
- danger / error；
- processing；
- result-unknown；
- needs-attention；
- refunded；
- destructive action。

青荷与素白主要切换 canvas、surface、text、line、brand、media 和 focus 关系。主题
切换不能改变布局、状态含义和行动等级。

## 6. CSS 与组件所有权

目标依赖方向：

```text
design-system tokens/base
        ↓
shared UI primitives
        ↓
entity / feature domain components
        ↓
page composition
        ↓
app shell
```

| 层 | 允许持有 | 禁止持有 |
| --- | --- | --- |
| `packages/design-system` | 两层令牌、浏览器基线、无业务 CSS recipe | API、DTO、业务状态、页面选择器 |
| `shared/ui` | Button、Field、StatusNotice、Surface、ActionGroup 等无领域 primitives | 订单、支付、商品等领域语义 |
| `entities/features` | 商品价格、订单状态、物流时间线、退款事实等领域展示 | 修改全局按钮和页面容器 |
| `pages` | 网格、阅读顺序、区域间距和响应式组合 | 重新定义 primitive 的颜色、焦点、禁用和错误状态 |
| `app` | 壳层、全局导航、页面主容器和跨域入口 | 领域卡片与业务状态机 |

页面级样式不得出现 `.product-page .button`、`.order-page .button` 之类覆盖基础组件的
规则。旧 `storefront.css` 和 `admin.css` 采用“迁移一个消费者、删除一段旧规则”的
方式缩小，不先复制到新文件再保留两套事实。

不为追求目录对称提前制造万能组件。只有首页、商品详情和订单详情三个原型共同证明
可复用的结构，才上升为 primitive 或 domain component。

## 7. 三个真实原型

原型直接修改现有真实路由、真实组件和受控真实状态，不另建演示专用页面。

### 7.1 首页

验证品牌气质、浏览节奏、商品图片、内容入口、全局壳层和移动端首屏。退出条件包括：

- 首屏只有一个明确浏览方向；
- 商品图片是主体，不被口号、徽章和按钮淹没；
- 首页不依赖大面积广告 Banner；
- 320px 起无横向溢出，键盘焦点顺序成立。

### 7.2 商品详情

验证商品媒体、标题、规格、价格、购买行动、说明和评价能否形成连续信息流。退出条件
包括：

- 规格与主要行动在桌面和移动端都清楚；
- 商品图裁切、缩放和替代文本一致；
- 评价不重新退化为重卡片矩阵；
- 库存与结算提示不伪装成商品卖点。

### 7.3 订单详情

验证高状态密度和跨域事实不会变成后端架构图。至少覆盖：

| 状态样本 | 必须证明 |
| --- | --- |
| 正常推进 | 当前事实、下一步和主要行动清楚 |
| 处理中 | 不显示成功，不诱导重复动作 |
| 结果未知 | 原键查询恢复与风险说明可见 |
| `NEEDS_ATTENTION` | 治理异常清楚但不冒充顾客已失败或已退款 |

订单商品、金额、地址、支付和履约仍可追溯，但按用户旅程排序；服务所有权只作为辅助
信任信息，不作为每个区域的视觉标题。

管理端暂不进入前三个原型。顾客端语言稳定后，再选择一个表格/治理密集页作为第四种
参考形态。

## 8. 分阶段实施

### V1：视觉审计与冻结基线

产出：

- 所有顾客端与代表管理端的桌面/移动截图索引；
- 页面 × 问题类型矩阵；
- CSS、选择器、断点、图片、动画和潜在死样式清单；
- 三个原型的状态样本与验收表；
- 令牌和 primitives 候选，不修改产品页面。

问题矩阵至少记录：页面、问题类型、严重程度、影响范围、对应组件、建议归属层、是否
涉及业务风险和证据路径。

退出门禁：审计范围与优先级冻结；没有未归属的全局视觉问题；三原型状态样本可稳定
复现；用户确认视觉方向后才进入 V2。

### V2：令牌、primitives 与全局壳层

建立两层令牌、命名断点、字体与间距尺度、按钮、字段、状态提示、表面、行动组、
Header、Footer 和主容器。基础组件覆盖 default、hover、focus-visible、disabled、
loading、error 和 destructive。

退出门禁：两主题语义状态一致；320px 无溢出；reduced-motion 生效；业务页面不新增
硬编码色值、宽度和基础控件覆盖；旧壳层规则已删除而非并存。

状态：已完成。最终证据为 182 个前端单元/契约测试、17 个 Playwright E2E、16 条
分层规则、两端类型检查与生产构建全部通过；内置浏览器确认两主题风险语义稳定、
顾客端商品图真实解码、管理端 320px 根页面无溢出且 Console 零错误。详见
[V2 设计系统与全局壳层收口](84-frontend-visual-v2-design-system-and-shell-20260802.md)。

### V3：首页、商品详情、订单详情原型

三个真实路由按第 7 节迁移。先用页面验证复用关系，再决定哪些模式上升为公共能力。
不在这一阶段批量改其余页面。

退出门禁：两个主题、四类订单状态、桌面/移动、键盘和浏览器控制台全部通过；用户
确认整体气韵后才允许扩散。

状态：已完成。首页、商品详情和订单详情已在真实路由上迁移；最终证据为 183 个
前端单元/契约测试、20 个 Playwright E2E、3 个 V3 专项用例、16 条分层规则、两端
类型检查和生产构建全部通过。审查同时修复 `PAYMENT_EXCEPTION` 被错误解释为可能
正在建立履约的问题，代码、单测、请求取证和内置浏览器均证明异常订单不会请求或
展示 Fulfillment。详见
[V3 三个真实原型收口](85-frontend-visual-v3-three-prototypes-20260802.md)。

### V4：商品发现链

迁移商品列表、搜索、索引、商品卡、商品网格、图片浏览和评价。保持 URL 中搜索、
分类、分页与可恢复状态，不用视觉重构隐藏降级来源。

状态：已完成。分类、搜索和分页已成为 URL 可恢复事实；搜索降级明确显示
`MYSQL_FALLBACK`；索引只保留真实入口；图片状态和公开评价保持同一发现旅程。最终
证据为 196 个前端单元/契约测试、23 个 Playwright E2E、3 个 V4 专项用例、16 条
分层规则、两端类型检查和生产构建全部通过。内置浏览器还发现并关闭传统滚动条下
`body min-width:20rem` 导致的 320px 全站横向溢出，目录、搜索、索引和商品详情
复验均无溢出且 Console warning/error 为 0。详见
[V4 商品发现链收口](86-frontend-visual-v4-product-discovery-20260802.md)。

### V5：交易与售后链

迁移购物袋、结算、订单列表、Payment、Fulfillment、评价资格、售后、寄回和退款。
最高规则是“代码边界强、视觉边界弱”；页面围绕当前事实和下一步组织，不按微服务
数量制造卡片。

状态：已完成。V5.1 已完成购物袋与结算迁移：设备袋与账户购物车保持两套事实，
金额不混算；结算按地址、权益、商品快照、金额试算、三域权威核对和提交恢复形成连续
旅程；库存不足保持 warning，订单结果未知使用稳定请求键与固定载荷恢复，不显示成功
或 danger。详见
[V5.1 购物袋与结算收口](87-frontend-visual-v5-1-bag-checkout-20260802.md)。

V5.2 已完成订单列表、Payment、Fulfillment 与评价资格入口迁移：订单改为连续事实行；
支付处理中、失败和创建结果未知分别使用 processing、warning、unknown，不再统一画成
success；确认收货只有 Fulfillment 返回 `SIGNED` 才显示成功，响应未知继续保留同一路径
查询与重试；`PAYMENT_EXCEPTION` 仍不请求或展示 Fulfillment。浏览器验收同时发现并
修复素白主题次级文字在柔和表面上 4.29–4.38:1 的对比度缺陷。最终证据为 206 个
前端单元/契约测试、28 个 Playwright E2E、2 个 V5.2 专项用例、16 条分层规则、
两端类型检查与生产构建全部通过。详见
[V5.2 订单、支付与履约收口](88-frontend-visual-v5-2-order-payment-fulfillment-20260802.md)。

V5.3 已完成售后列表、详情、寄回、仓库事实与退款迁移：逆向链按“当前售后事实 ->
当前处理方与下一步 -> 寄回/仓库 -> 渠道退款 -> 不可变退款商品快照”连续呈现；
寄回和取消结果未知使用 unknown，不与成功共用反馈；退款 `PROCESSING` 使用
processing，`NEEDS_ATTENTION` 使用 attention，顾客端只显示平台授权治理说明，
不暴露 Payment 管理补偿命令。迁移后已按消费者归零证据清除旧
`checkout-section`、`checkout-summary`、`payment-state`、`order-card` 和
`return-shipment-form` 全局规则，仍被权益页使用的 `order-status-badge` 保留。

V5 最终证据为 212 个前端单元/契约测试、31 个 Playwright E2E、3 个 V5.3 专项
用例、16 条分层规则、两端类型检查和生产构建全部通过。详见
[V5.3 售后与退款收口](89-frontend-visual-v5-3-after-sale-refund-20260802.md)。

### V6：账户、沟通与管理端

迁移登录注册、地址、权益、通知、Chat，以及管理端导航、表格、筛选、状态、治理命令
和审计反馈。管理端共享品牌与语义令牌，但使用更高信息密度，不照搬商城版式。

V6.1 已完成登录、注册与账户首页迁移：认证页使用正式页面容器、表面、字段、按钮和
危险状态提示；账户首页按顾客任务组织入口，不再把 Trade、Marketing 等内部服务名
作为主文案；购物袋合并的 pending、succeeded、unknown、failed 和
ownership-conflict 分别使用 processing、success、unknown、danger 和 attention；
服务端退出 503 保持本机会话与刷新凭据，只有顾客明确点击“仅清除此设备”后才清除。

V6.1 最终证据为 220 个前端单元/契约测试、35 个 Playwright E2E、4 个 V6.1 专项
用例、16 条分层规则、两端类型检查和生产构建全部通过。详见
[V6.1 登录、注册与账户首页收口](90-frontend-visual-v6-1-identity-account-20260802.md)。

V6.2 已完成收货地址与优惠权益迁移，并在视觉迁移前关闭两项隐藏边界：

- 地址写入的网络、超时、非法响应和 5xx 不再显示普通失败或诱导直接重提，而是明确
  标记 unknown，要求先重新读取 Identity 事实；
- 权益旧顶层 store 已迁入独立 benefit entity，显式接收 owner/token 上下文；账户或
  token 切换会使旧请求失效，响应中的 `userId` 不属于当前 owner 时拒绝提交到页面。

地址仍不伪造后端不存在的 expected version 或幂等键。权益 AVAILABLE、LOCKED 和
REDEEMED 分别使用 success、processing 和 neutral，不再共用一个订单状态徽章。
V6.2 最终证据为 228 个前端单元/契约测试、38 个 Playwright E2E、3 个 V6.2 专项
用例、17 条分层规则、两端类型检查和生产构建全部通过。详见
[V6.2 地址与优惠权益收口](91-frontend-visual-v6-2-address-benefits-20260802.md)。

V6.3 已完成通知与 Chat 迁移：

- Notification 使用独立 owner/token 代次和不透明 keyset 游标；已读网络、超时、非法
  响应和 5xx 保持 unknown，只通过权威列表 GET 核对；
- Notification 雪花 ID 改为 JSON string，并以真实 MySQL 与 Gateway 对
  `2084092130033098754` 完成逐字符一致验证；
- Chat 旧顶层 store 迁入独立 entity，账户或 token 切换后旧请求只能写入废弃
  workspace，另一账户 pending 正文和重试键不能进入当前页面；
- WebSocket 只承担实时提示，REST/MySQL 历史仍为权威；实时中断、已读失败、附件隔离
  和发送结果未知保持四种不同语义。

V6.3 最终证据为 239 个前端单元/契约测试、41 个 Playwright E2E、3 个 V6.3 专项
用例、19 条分层规则、两端类型检查与生产构建全部通过；Notification 还重新通过真实
MySQL、RocketMQ、Gateway、SMTP 故障恢复、权限和零残留门禁。详见
[V6.3 通知与 Chat 收口](92-frontend-visual-v6-3-notification-chat-20260803.md)。

V6.4.1 已完成 Governance 高风险代表页。页面不再把所有失败统一显示为 danger：
网络、超时、非法响应、5xx 和事实错归保持 unknown；明确 4xx 保持 rejected；unknown
命令按管理员作用域持久化业务编号、命令 ID 和原因，只能由同一命令的
ACCEPTED/REJECTED 审计或权威 POST 响应收敛。视觉迁移使用共享 primitives，同时
保留四域只读对账、稳定幂等键和追加式审计的高信息密度。

V6.4.1 最终证据为 246 个前端单元/契约测试、43 个 Playwright E2E、2 个 V6.4 专项
用例、20 条分层规则、两端类型检查和生产构建全部通过；内置浏览器还逐字符核对页面
命令 ID 与 Mock 权威记录，并确认 503 后先 unknown、审计后才 ACCEPTED。真实
Payment/MySQL 资金证据继续引用第 19、20、69 号文档。详见
[V6.4.1 Governance 高风险代表页](93-frontend-visual-v6-4-1-governance-20260803.md)。

V6.4.2 已完成 Fulfillment 第二代表页。只读比较 Inventory 与 Fulfillment 后，选择
同时承载正向状态机、逆向状态机、稳定事件 ID、异常恢复权限和 Redis 可重建投影的
Fulfillment，而没有为扩大批次同时迁移 Inventory。页面 API 编排和结果未知恢复已
迁入独立 `admin-fulfillment` entity：

- 命令提交前按员工 `operatorId` 持久化业务号、命令身份与原始载荷；
- 网络、超时、非法响应和 5xx 保持 unknown，明确 4xx 保持 rejected；
- unknown 时禁止生成第二条命令，只允许读取权威事实或原样重试；
- 物流轨迹可用同一 `externalEventId` 与事实载荷核对；
- 异常恢复响应模型不公开命令 ID，因此只读状态变化不能被错误归因于原命令，必须
  使用原 `Idempotency-Key` 重试确认；
- `type="number"` 的真实浏览器运行时值按 `string | number` 建模并统一归一化，关闭
  了单元测试未发现的 `.trim()` 运行时异常。

V6.4.2 最终证据为 252 个前端单元/契约测试、45 个 Playwright E2E、4 个 V6.4 专项
用例、21 条分层规则、两端类型检查和生产构建全部通过；Fulfillment 后端正向、异常
恢复、权限、并发与逆向退货专项 19/19 通过。浏览器逐字段核对轨迹事件 ID、发生时间、
异常恢复命令 ID 与原因两次完全一致，并确认 320/390px 无根级横向溢出、axe
serious/critical 为 0、Console warning/error 为 0。真实 MySQL Spatial、Redis GEO、
异常恢复和退货链继续引用第 64、69 号文档。详见
[V6.4.2 Fulfillment 第二代表页](94-frontend-visual-v6-4-2-fulfillment-20260803.md)。

V6.4.3 的 Inventory 切片已完成。库存调整按员工保存 `movementNo + 完整载荷`；
网络、超时、非法响应和 5xx 保持 unknown；普通库存 GET 不公开 movementNo，因此
即使数量或版本变化也不能归因原调整成功，只有原流水同载荷重试收到权威响应后才
accepted。仓库创建接口没有额外幂等键，结果未知时只按唯一代码和名称读取核对，不
提供盲目重复 POST。浏览器验收还发现并修复 HTML pattern 在 Chromium `v` 正则模式
下未转义连字符的问题。

Inventory 切片最终证据为 257 个前端单元/契约测试、47 个 Playwright E2E、6 个 V6.4
专项用例、22 条分层规则、两端类型检查和生产构建全部通过；Inventory 后端权限、
幂等、事务、Outbox 和 1000 并发预占专项 17/17 通过。详见
[V6.4.3 Inventory 管理工作区](95-frontend-visual-v6-4-3-inventory-20260803.md)。

V6.4.3 的 Marketing 切片已完成。规则创建与权益发放不再共享一种错误和重试语义：

- 规则创建没有稳定幂等键，也没有管理端规则查询；响应丢失后完整载荷保持 unknown，
  页面不提供重复 POST，也不把后续唯一冲突归因原命令成功；
- 权益发放按员工保存 `userId + ruleCode + grantKey`，503 后只允许三项身份原样重试；
- 后端新增专项断言同顾客、同键、同规则返回同一权益，同键换规则冲突，不同顾客隔离；
- 规则完整载荷或权益 owner/rule 返回错归时保持 unknown；
- operator 或 token 切换后旧响应静默作废；
- 旧 `.admin-work-grid` 已确认零消费者并删除。

Marketing 切片最终证据为 264 个前端单元/契约测试、49 个 Playwright E2E、8 个 V6.4
专项用例、23 条分层规则、两端类型检查和生产构建全部通过；Marketing 后端事务、
唯一约束与权限专项 8/8 通过。详见
[V6.4.3 Marketing 管理工作区](96-frontend-visual-v6-4-3-marketing-20260803.md)。

V6.4.3 的 Catalog 切片已完成。当前后端具备商品创建、编辑、上下架和媒体等管理命令，
但没有管理端商品列表或详情 GET 契约；Foundation 也没有对应管理写 API。因此本批没有
为了视觉迁移扩建完整经营后台，而是把现有页面收敛为公开 `ACTIVE` 商品观察窗：

- 分类和商品通过公开 API 读取，不携带员工 Bearer Token；
- 商品、分类、品牌和 64 位业务 ID 均执行运行时契约校验，ID 始终保持字符串；
- 筛选与分页使用真实 `page / size / total`，错页或重复商品 ID 响应拒绝写入；
- 最后一次请求胜出，operator/token 切换后迟到响应静默作废；
- 公开读可能来自副本，页面明确称为“公开投影”，不宣称最新主库经营事实；
- 503 刷新保留上一次已知商品，不把读取失败伪装成空目录；
- 商品图片真实渲染，断链时局部降级为“无图片”；
- 旧 `.admin-table-wrap` 已确认零消费者并删除。

Catalog 切片最终证据为 270 个前端单元/契约测试、51 个 Playwright E2E、10 个 V6.4
专项用例、24 条分层规则、两端类型检查和生产构建全部通过；Catalog 后端权限、草稿
公开隔离、发布、乐观锁、金额和媒体降级专项 3/3 通过。详见
[V6.4.3 Catalog 管理观察窗](97-frontend-visual-v6-4-3-catalog-20260803.md)。

V6.4.3 的 After-sale 切片已完成。审核接口没有独立命令 ID，但 Trade DTO 返回审核
原因、状态和批准时间；后端相同目标状态只读返回已有事实。因此前端建立
authority-first 恢复协议：

- 5xx 后按管理员保存售后号、审核决定和完整原因，页面保持 unknown；
- 必须先按售后号读取 Trade 权威事实，不能直接重试或开始第二条审核；
- 相同决定、相同原因和合法状态路径只确认业务结果，并明确不伪造命令身份；
- 权威事实仍为 `APPLIED` 时才开放原决定与原因的只读重试；
- 其他状态或不同原因拒绝归因，防止冒领其他管理员的审核；
- 九种后端状态完整进入筛选，补回旧页面遗漏的 `RECEIVED` 和 `CANCELED`；
- 页面连续展示当前处理方、审核事实和不可变退款商品行，不按服务数量制造卡片；
- 旧 operations API 聚合入口及七组零消费者管理 CSS 已删除。

After-sale 切片最终证据为 278 个前端单元/契约测试、54 个 Playwright E2E、13 个 V6.4
专项用例、25 条分层规则、两端类型检查和生产构建全部通过；Trade 整单快照、审核、
状态推进和角色隔离专项 4/4 通过。详见
[V6.4.3 After-sale 管理审核工作区](98-frontend-visual-v6-4-3-after-sale-20260803.md)。

V6.4.3 的 Review 切片已完成。平台回复和举报审核都拥有稳定命令身份：

- 回复使用 `Idempotency-Key`，审核使用 body `commandId`；
- 5xx 后按管理员冻结举报快照、命令 ID 和完整载荷，只允许原样重放；
- 管理举报 GET 不含命令 ID/审核原因，公开评价也不能证明命令身份，不能被用来
  猜测原命令成功；
- 回复返回必须匹配评价、商品、评分、原评价正文和冻结回复；
- 审核返回必须匹配 report、review、commandId、resolution 和合法状态迁移；
- `UPHELD/REJECTED` 与 `PUBLISHED/HIDDEN` 分开表达，驳回举报不会重新发布已隐藏评价；
- 64 位身份保持字符串，operator/token 切换后迟到响应静默作废；
- 旧 Review API 工厂与五组零消费者全局 CSS 已删除。

Review 切片最终证据为 287 个前端单元/契约测试、56 个 Playwright E2E、15 个 V6.4
专项用例、26 条分层规则、两端类型检查和生产构建全部通过；Catalog Review 后端
事务、幂等、审核、评分和权限专项 5/5 通过。详见
[V6.4.3 Review 评价治理工作区](99-frontend-visual-v6-4-3-review-20260803.md)。

V6.4.3 的 Chat 切片已完成。管理端没有重写 Foundation Chat 协议，而是在其上建立
独立 `admin-chat` entity：

- 页面只传入 `authorized / operatorId / accessToken`，不再直接读取员工身份来决定
  消息权限或发送所有者；
- 每个员工与 token 代次拥有独立 workspace；迟到 REST、旧 WebSocket 和旧轮询只能
  写入废弃状态；
- pending 回复 key 按员工分区，重启后恢复原 `clientMessageId` 和正文；
- 未认领队列只读摘要，认领提交后丢响应时必须由客服成员权威事实确认后才读取正文；
- 发送请求未到达时保持 unknown，只允许原客户端消息键与原正文查询/重试；
- 回复 unknown 时禁止关闭会话；关闭提交后丢响应只有 `CLOSED` 权威事实能收敛；
- 所有会话、消息、附件、已读和票据响应执行字符串 ID 与目标身份运行时校验；
- WebSocket 继续只作实时提示，REST/MySQL 历史为权威，附件入口保持关闭。

Chat 切片最终证据为 295 个前端单元/契约测试、57 个 Playwright E2E、16 个 V6.4
专项用例、27 条分层规则、两端类型检查和生产构建全部通过；Chat entity 8/8、
既有 M8 Chat 浏览器专项 3/3，platform-common 21/21、chat-service 59/59。详见
[V6.4.3 Chat 客服工作区](100-frontend-visual-v6-4-3-chat-20260803.md)。

V6.4.4 的管理首页已完成。首页 API 与访问竞态迁入独立 `admin-analytics` entity，
员工或 token 切换后旧请求不能污染当前投影；日期范围、日汇总、金额、计数、新鲜度
和商品身份均执行运行时合同。审查同时发现 Analytics 商品 ID 仍以 JSON number 暴露，
现已通过后端 Jackson 字符串序列化与 Foundation `BusinessId` 收敛，19 位 ID 在浏览器
逐字符一致。刷新 503 时保留上一份已知投影与原生成时间，不伪装空数据或成功。

管理首页最终证据为 301 个前端单元/契约测试、58 个 Playwright E2E、17 个 V6.4
专项用例、28 条分层规则、两端类型检查和生产构建全部通过；Analytics 后端专项 5/5。
内置浏览器确认桌面和 320px 无根溢出、单一 `main`、控制台 warning/error 为 0。详见
[V6.4.4 管理首页与 V6 收口](101-frontend-visual-v6-4-4-operations-home-20260803.md)。

V6 已关闭。只有出现两个以上真实消费者后，才考虑把 Governance、Fulfillment、
Inventory、Marketing 等管理端布局模式上升为共享 primitive。

### V7：全站收口与 GitHub 交付

完成全路由视觉回归、文案去广告化、死 CSS/组件清理、图片优化、演示数据、部署文档、
README 截图和 GitHub v1.0 发布材料。旧视觉规则、临时原型入口和重复资源必须有明确
零残留证据。

V7.1 已完成全站交付审计与冻结：建立 20 个顾客端路由、13 个管理端路由、52 个应用
生产 Vue 文件和 3 张正式图片的可重复静态清单；新增 `pnpm check:delivery`，阻止
临时/阶段入口、旧项目标识、重复路由和六个退役选择器回流；修正
`frontend/README.md` 与项目总计划的当前坐标。三张 PNG 合计 5.59 MiB、演示账号、
生产 History fallback、README 截图和 GitHub 元数据继续作为未完成交付项，不冒充
完成。详见 [V7.1 全站交付审计与冻结](102-frontend-visual-v7-1-delivery-audit-20260803.md)。

V7.2 已完成响应式图片交付：保留三张 PNG 作为源与 fallback，按实际展示宽度生成
18 个 AVIF/WebP 变体，总计 860.6 KiB，相对 5.59 MiB 原图集合下降 85.0%；新增共享
`PjResponsiveImage` 和商品图片交付合同，首页、商品卡、详情、购物袋、管理 Catalog
与履约插图均接入 `picture/srcset/sizes`。管理端复用顾客端 public 目录，没有复制
图片资产。完整 60 条真实 Chromium 用例证明 `currentSrc` 和 HTTP 响应为 AVIF，PNG
fallback 图片请求为 0；首轮还识别并修正了把 Vite `?import` 脚本元数据误算成图片
请求的测试取证错误，没有放宽产品断言。详见
[V7.2 响应式图片交付](103-frontend-visual-v7-2-image-delivery-20260803.md)。

V7.3 已完成演示夹具与生产静态交付：固定公开夹具账号并让错误密码真实返回 401；
两端增加生产 preview，同源代理到受控 API；新增 Nginx History fallback、哈希资源
与稳定图片分级缓存、API/WS 边界、双镜像 target、Compose 项目标识、版本标签和
只切旧镜像不重建的回退说明。3 条生产 Chromium 专项证明顾客商品/订单和管理治理
深层路由刷新、同源 API 与守卫恢复。Docker Desktop 本批保持关闭，真实 Nginx
镜像、Header/404 和健康检查进入 V7.4 发布候选互斥窗口，不伪造运行证据。详见
[V7.3 演示夹具与生产静态交付](104-frontend-visual-v7-3-demo-static-deployment-20260803.md)。

V7.4 已完成 GitHub 展示与本地发布候选：真实构建顾客端和管理端双 Nginx 镜像，
补齐 OCI 版本/提交/时间/来源元数据；运行验证 `index.html` no-store、哈希资源
immutable、稳定图片非 immutable、缺失资源 404、同源 API 和双容器 HEALTHCHECK；
两个不可变标签完成 `candidate → baseline → candidate` 的实际 image ID 切换。
README、三张最终截图、CHANGELOG、SECURITY 和人工 Release checklist 已完成。
真实浏览器还发现并清理公开夹具中的 `M4 Admin` 等阶段标签，并把该类残留加入失败
关闭门禁。LICENSE 与 GitHub origin 仍由仓库所有者决定。详见
[V7.4 GitHub 展示与发布候选](105-frontend-visual-v7-4-release-candidate-20260803.md)。

## 9. 可验收标准

每个实现批次必须同时具备代码证明、自动化测试和真实浏览器证据；不能只凭截图，也
不能只看日志。

| 维度 | 最低门禁 |
| --- | --- |
| 视口 | 320、375/390、768、1280/1440 代表宽度 |
| 主题 | 青荷、素白；语义状态含义一致 |
| 交互 | 键盘、focus-visible、hover、disabled、loading、错误 |
| 动效 | `prefers-reduced-motion` 下关闭或降级 |
| 无障碍 | axe serious/critical 0，单一 `main`，语义标题顺序合理 |
| 布局 | 横向溢出 0；容器和网格不在页面自行创造新宽度体系 |
| 代码 | 分层规则通过；业务页不直接使用色值；无跨层样式覆盖 |
| 自动化 | 当前 Vitest、Playwright、类型检查和生产构建全通过 |
| 浏览器/F12 | 真实点击、路由、刷新恢复、请求与控制台检查；warning/error 0 |
| 高风险状态 | 代码 + 自动化 + 真实业务证据三层齐全，不以视觉夹具替代后端事实 |

受控 HTTP 夹具可验证页面状态与异常交互，但不能替代 MySQL、RocketMQ、Redis 或所有者
域事实。若视觉批次没有修改后端契约，可引用已经封存的 M0–M8 真实链证据；一旦触碰
业务判断或请求顺序，必须重新运行对应真实链。

## 10. 单机执行与回退

Y7000P 上继续采用串行、按需和时间换空间：

- 人工浏览器夹具、Playwright 和真实中间件验证不并行争抢 18000/18200/18201；
- 不为视觉批次常驻完整中间件；需要真实交易状态时按互斥 Profile 串行启动；
- 图片转换和生产构建串行执行，避免与 Docker、浏览器录制同时占用内存；
- 只停止本批明确启动且已核对命令行的进程，不结束 Codex 内部 Node 运行时；
- 不修改机器级 Node、代理、网卡、Docker 数据或全局镜像源。

每批只迁移一组消费者。旧规则在新规则和三层门禁通过后才删除；发生问题时回退当前
视觉切片，不回退业务状态、数据库迁移或用户已有工作树。禁止 reset、checkout、clean
和批量覆盖。

## 11. 文档与完成定义

V1 的最终截图索引、问题矩阵、样式资产和三个原型冻结状态见
[V1 审计与冻结报告](82-frontend-visual-v1-audit-20260801.md)。V1 还发现支付结果未知后
忙碌状态不归零及已读到权威支付事实后设备 pending 未收敛两项 P0；它们必须先作为
独立业务缺陷完成三层修复，不能由视觉重构遮盖。V2–V7
各自建立独立证据报告，历史数字保留为时间线，不用最新数字覆盖旧批次。

V2 的两层令牌、共享 primitives、两端壳层、320px 溢出治理、对比度修复、商品图片
断链修复和三层验收见
[V2 设计系统与全局壳层收口](84-frontend-visual-v2-design-system-and-shell-20260802.md)。

V3 的首页、商品详情、订单详情、支付异常展示边界、桌面/移动回归和真实浏览器验收见
[V3 三个真实原型收口](85-frontend-visual-v3-three-prototypes-20260802.md)。

V4 的 URL 分页、搜索降级来源、真实索引、图片状态、公开评价、完整自动化与传统
滚动条 320px 复验见
[V4 商品发现链收口](86-frontend-visual-v4-product-discovery-20260802.md)。

V5.1 的设备/账户购物袋分离、三域权威结算、库存不足阻断、订单结果未知、完整前端
回归与内置浏览器验收见
[V5.1 购物袋与结算收口](87-frontend-visual-v5-1-bag-checkout-20260802.md)。

V5.2 的订单连续行、Payment 状态语义、Fulfillment 签收边界、评价资格入口、素白
对比度修复、完整前端回归与内置浏览器验收见
[V5.2 订单、支付与履约收口](88-frontend-visual-v5-2-order-payment-fulfillment-20260802.md)。

V5.3 的售后连续旅程、寄回/取消未知结果、退款治理语义、旧全局 CSS 清理、完整前端
回归与本地浏览器入口限制见
[V5.3 售后与退款收口](89-frontend-visual-v5-3-after-sale-refund-20260802.md)。

V6.1 的认证请求顺序、安全回跳、游客袋合并五态、退出结果未知、本地明确清除边界与
完整前端回归见
[V6.1 登录、注册与账户首页收口](90-frontend-visual-v6-1-identity-account-20260802.md)。

V6.2 的地址写入未知结果、权威重读、权益 owner/token 代次、生命周期语义、旧 store
与旧 CSS 清理及完整前端回归见
[V6.2 地址与优惠权益收口](91-frontend-visual-v6-2-address-benefits-20260802.md)。

V6.3 的通知 owner/token 代次、不透明游标、已读结果未知、64 位字符串 ID、Chat
workspace 隔离、稳定客户端消息键、实时/历史事实边界、真实 Notification 链和完整
前端回归见
[V6.3 通知与 Chat 收口](92-frontend-visual-v6-3-notification-chat-20260803.md)。

V6.4.1 的管理员作用域 pending 命令、5xx 结果未知、审计权威收敛、同键重试、
Governance 管理端原型、窄屏表格治理、浏览器正则兼容和完整前端回归见
[V6.4.1 Governance 高风险代表页](93-frontend-visual-v6-4-1-governance-20260803.md)。

V6.4.2 的 Fulfillment 命令 pending、轨迹事件 ID、异常恢复原键确认、正逆向履约
连续布局、数字输入运行时类型修复、零消费者 GEO CSS 清理及完整前后端回归见
[V6.4.2 Fulfillment 第二代表页](94-frontend-visual-v6-4-2-fulfillment-20260803.md)。

V6.4.3 Inventory 切片的 movementNo 原载荷恢复、库存重读不可归因、仓库唯一事实
核对、员工上下文隔离、浏览器 pattern 修复、1000 并发后端专项和零消费者旧 CSS
清理见
[V6.4.3 Inventory 管理工作区](95-frontend-visual-v6-4-3-inventory-20260803.md)。

V6.4.3 Marketing 切片的两类非对称命令恢复、原 `grantKey` 重试、规则 unknown
契约缺口、返回事实校验、员工/token 隔离、后端幂等专项与零消费者 CSS 清理见
[V6.4.3 Marketing 管理工作区](96-frontend-visual-v6-4-3-marketing-20260803.md)。

V6.4.3 Catalog 切片的公开 `ACTIVE` 投影边界、字符串业务 ID、真实分页、并发读取
隔离、读失败保留已知事实、商品图片降级、后端公开隔离专项和零消费者 CSS 清理见
[V6.4.3 Catalog 管理观察窗](97-frontend-visual-v6-4-3-catalog-20260803.md)。

V6.4.3 After-sale 切片的 authority-first 审核恢复、原决定与原因冻结、业务结果与命令
身份区分、九状态筛选、退款快照连续布局、员工隔离、后端整单专项和旧 API/CSS 清理见
[V6.4.3 After-sale 管理审核工作区](98-frontend-visual-v6-4-3-after-sale-20260803.md)。

V6.4.3 Review 切片的回复/审核双幂等身份、原命令重放、审核结论与评价可见性分离、
64 位字符串 ID、员工隔离、后端审核事务专项和旧 API/CSS 清理见
[V6.4.3 Review 评价治理工作区](99-frontend-visual-v6-4-3-review-20260803.md)。

V6.4.3 Chat 切片的客服成员授权、workspace 代次、逐员工 pending 回复、认领/发送/
关闭结果未知、WebSocket 与 REST 权威分工、字符串 ID 契约和旧 store/CSS 清理见
[V6.4.3 Chat 客服工作区](100-frontend-visual-v6-4-3-chat-20260803.md)。

V6.4.4 管理首页的权限入口、Analytics 访问代次、日期与投影合同、64 位字符串身份、
503 已知投影保留、旧 API/CSS 清理和 V6 最终门禁见
[V6.4.4 管理首页与 V6 收口](101-frontend-visual-v6-4-4-operations-home-20260803.md)。

V7.1 的全站路由、源码、样式、图片和发布材料审计，以及交付失败关闭规则见
[V7.1 全站交付审计与冻结](102-frontend-visual-v7-1-delivery-audit-20260803.md)。

V7.2 的确定性图片转换、共享响应式 primitive、体积预算、管理端资源复用和真实
Chromium AVIF/Fallback 网络证据见
[V7.2 响应式图片交付](103-frontend-visual-v7-2-image-delivery-20260803.md)。

V7.3 的夹具账号边界、错误登录、生产构建深层刷新、同源 API、Nginx/Compose、
缓存、镜像标签与回退边界见
[V7.3 演示夹具与生产静态交付](104-frontend-visual-v7-3-demo-static-deployment-20260803.md)。

V7.4 的真实双 Nginx 镜像、OCI 元数据、Header/404/代理/健康检查、不可变标签回退、
最终截图和 GitHub 发布材料见
[V7.4 GitHub 展示与发布候选](105-frontend-visual-v7-4-release-candidate-20260803.md)。

视觉重构完成不等于“页面变好看”，而是同时满足：

1. 统一令牌和 primitives 真正被页面消费；
2. 用户旅程连续，复杂领域边界没有变成视觉隔墙；
3. 正常、处理中、结果未知和治理异常都准确可辨；
4. 两主题、全视口、键盘、无障碍和浏览器门禁通过；
5. 旧 CSS、重复组件、临时资源和开发入口清理完毕；
6. GitHub v1.0 的演示、截图、启动与证据文档可以独立复现。

达到以上条件后，当前《素简记》冻结为完整自营 B2C 作品；多商户、平台分账与 Go
异构服务仍只进入未来独立《素简记 Pro》。
