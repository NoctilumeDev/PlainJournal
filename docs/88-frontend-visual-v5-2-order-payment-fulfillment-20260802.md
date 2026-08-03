# 前端视觉 V5.2：订单、支付与履约收口

> 完成日期：2026-08-02  
> 状态：V5.2 已完成；V5 整体仍在进行中  
> 范围：订单列表、订单详情、Payment、Fulfillment、评价资格入口  
> 硬边界：未修改 API、DTO、幂等键、状态机、权限、所有者隔离或领域裁决权

## 1. 本批结论

V5.2 将订单列表从重卡片阵列迁移为连续事实行，并继续收束订单详情：

```text
当前订单事实
  -> 下一步行动
  -> 商品与地址快照
  -> 支付事实
  -> 履约与物流事实
  -> 评价资格入口
```

Payment 与 Fulfillment 仍是独立所有者域，但不再以后台服务面板的方式竞争视觉注意力。
页面没有复制状态机，也没有为了视觉顺畅改变请求顺序。支付处理中、失败、结果未知和
成功分别使用不同语义；确认收货只有 Fulfillment 返回 `SIGNED` 才能显示成功。
`PAYMENT_EXCEPTION` 仍只展示订单与支付核对事实，不请求或暗示履约已经开始。

售后申请、寄回、仓库验收和退款没有混入本批，留给 V5.3。

## 2. 代码证明

### 2.1 订单列表

`OrderListPage.vue` 使用 `PjPageContainer`、`PjStatusNotice` 和 `PjButton`，把订单改为
连续 `order-row`。时间、订单号、当前状态、商品摘要、应付金额、事实说明和详情入口
保持同一阅读方向。加载、空状态、错误和取消结果未知均使用共享状态 primitive。

分页、倒序、owner-scoped 取消 pending 和同一路径安全重试仍由原 order store 持有。
本批没有把取消状态复制到页面，也没有把 `CANCELING` 解释为 `CANCELED`。

### 2.2 Payment

`OrderPaymentSection.vue` 将反馈改为结构化 `{ tone, title, message }`：

| Payment 事实 | 视觉语义 |
| --- | --- |
| `PROCESSING` | processing |
| `FAILED` | warning |
| 创建结果未确认 | unknown |
| `SUCCESS` | success |

原实现把所有 `feedback` 统一渲染为 success，可能使“处理中、失败或未知”的反馈外观与
成功相同。本批只修展示语义，没有改变稳定支付键、owner-scoped pending、按键查询、
并发创建合并或支付与取消互斥。

### 2.3 Fulfillment

`OrderFulfillmentSection.vue` 的刷新反馈分为 processing 与 success；确认收货结果未知
继续只由专用 unknown notice 表达，不再落入通用 neutral/success 提示。

履约插图继续承担路线事实解释，但移除 `backdrop-filter` 毛玻璃，并把兼容别名令牌
迁移到 `--pj-surface-*`、`--pj-border-*`、`--pj-text-*` 和 `--pj-brand-*` 正式语义
令牌。物流位置、履约时间线、追加轨迹和二次确认均保留。

### 2.4 评价资格入口

`OrderReviewSection.vue` 最小迁移到 `PjButton` 与 `PjStatusNotice`，把资格收敛、提交
成功、提交结果未知和读取失败分开表达。评价 store、一次性资格和稳定提交内容没有
改变；完整售后链不在本组件内扩张。

### 2.5 素白对比度

V5.2 专项 axe 首次运行发现：素白主题 `#71716c` 次级文字在 soft/processing 表面上
只有 4.29–4.38:1。设计系统将 `--pj-palette-ink-650` 收紧为 `#696964`，在对应表面
达到 4.83 与 4.93:1；新增 token test 固化最低 4.5:1。该修复在设计令牌层完成，没有
给订单页面添加局部颜色补丁。

## 3. 三层证据

| 高风险边界 | 代码证明 | 自动化测试 | 真实运行证据 |
| --- | --- | --- | --- |
| 支付处理中不等于成功 | feedback tone 按 Payment 状态计算；取消与 in-flight 继续互斥 | `OrderDetailView.test.ts` 与 V5.2 E2E 断言 processing、无 success、无取消按钮 | 内置浏览器实际创建支付单后只出现两个 processing notice；请求与原 M4/M8 支付真实链证据一致 |
| 支付失败/未知不伪造成功 | FAILED 为 warning；submission unknown 为 unknown，保留原键 | 新增 FAILED、503 + 按键 404 两类单测；既有 payment store 幂等/owner 测试继续通过 | V5.2 浏览器故障夹具与 M4 真实恢复证据证明未知状态可查询和原键重试 |
| `PAYMENT_EXCEPTION` 不启动履约 | `orderMayHaveFulfillment` 不包含异常状态 | 订单详情单测和 V3/V5.2 E2E 均断言无 Fulfillment 请求与区域 | 浏览器请求取证只出现 Trade 与 Payment；M0–M8 第 69 号审查继续提供真实领域事实 |
| 确认收货只接受 `SIGNED` | receipt-confirmation store 只在 `SIGNED` 清 unknown；页面成功反馈同样受限 | store 测试、订单详情响应丢失测试和 V5.2 503 E2E 均保持 unknown | 浏览器故障链 POST 503 后继续显示 unknown，不出现订单完成；真实履约链沿用第 78 号证据 |
| owner 与权限隔离 | 页面只传访问上下文；stores 拒绝账户切换后的迟到响应 | Payment、Fulfillment、订单 store 的 owner/token、并发和迟到响应测试全部通过 | 本批未改权限/API；Gateway 与领域 403/404 真实证据引用第 69、77、78 号文档 |
| 双主题状态含义一致 | 状态令牌不在主题块覆盖；次级文字由设计系统统一裁决 | 设计系统 6 项测试、axe serious/critical 0 | 内置浏览器青荷/素白切换后状态、布局与完成事实不变，Console warning/error 为 0 |

受控 HTTP 夹具只证明页面状态、请求和恢复交互，不替代 MySQL、RocketMQ 或领域权限。
本批没有修改后端契约和业务判断，因此真实业务层引用 M0–M8 总审查及第 77、78 号
分层收口证据；如果后续改动状态机或请求协议，必须重新运行对应真实链。

## 4. 自动化门禁

针对性验证：

```text
订单列表、订单详情、评价入口单测       9 / 9
V5.2 专项 Playwright                  2 / 2
```

最终 `pnpm check`：

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 42 / 42 |
| UI primitives | 5 / 5 |
| Storefront | 141 / 141 |
| Admin | 12 / 12 |
| 前端单元/契约测试合计 | 206 / 206 |
| 分层规则 | 16 / 16 |
| 分层文件 / 相对导入 | 107 / 223 |
| Playwright 全量 Mock E2E | 28 / 28 |
| V5.2 专项 Playwright | 2 / 2 |
| 类型检查 | 全部通过 |
| 顾客端生产构建 | 通过 |
| 管理端生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 93.48 kB / gzip 14.13 kB
Storefront JS  329.46 kB / gzip 101.62 kB
Admin CSS      28.61 kB / gzip 5.51 kB
Admin JS      196.19 kB / gzip 63.46 kB
```

## 5. 浏览器与请求取证

V5.2 专项 Playwright 只启动 Mock API 18090 与 Storefront 18200，单 worker 串行执行：

- 订单列表为两条连续事实行，不再出现 `order-card`；
- 订单列表、待支付详情和完成详情在 320、390、1280px 无横向溢出；
- 创建支付单实际捕获 Trade 订单 GET 与 Payment POST 200；
- Payment `PROCESSING` 只显示 processing，不出现 success，取消入口关闭；
- `PAYMENT_EXCEPTION` 实际请求 Trade 与 Payment，不请求 Fulfillment；
- 确认收货 POST 503 后 owner read 仍为 `SHIPPED`，页面保持 unknown；
- 青荷与素白都通过 axe，serious/critical 为 0；
- 非故障注入的 Console warning/error 为 0。

内置浏览器另行完成正常路径：

- 桌面订单列表 `clientWidth=scrollWidth=1265`，两笔订单阅读顺序清楚；
- 实际点击创建支付单后，DOM 中 Payment 主状态与反馈均为 processing，取消按钮消失；
- 完成订单真实加载履约插图、支付、签收、位置、时间线与评价资格；
- 青荷与素白切换不改变状态语义，根页面无溢出；
- 浏览器日志只有 Vite debug 连接信息，warning/error 为 0。

结论不依赖日志：请求断言、DOM 语义、状态 class、图片自然尺寸、axe、根布局尺寸和
自动化 store 测试共同构成证据。

## 6. 遗留样式与单机边界

本批没有盲删 `storefront.css` 中全部 `order-card`、`order-status-badge`、
`payment-state` 或 `checkout-section` 规则，因为 `AfterSaleListView`、
`AfterSaleProgress` 和 `AfterSaleWorkspace` 仍有真实消费者。订单列表已不再消费旧
order-card 规则；待 V5.3 迁移售后链后，再按消费者归零证据删除对应旧样式。

本批没有启动 Docker、Java、Redis、Nacos、RocketMQ 或完整中间件。完整门禁、
专项 E2E 与内置浏览器严格串行；人工验收只启动 Mock API 与 Storefront 两个 Node
进程，不修改机器级 Node、代理、网卡、Docker 数据或环境变量。

## 7. 下一坐标

V5.3 只处理：

1. 售后申请与当前审核事实；
2. 顾客寄回、仓库收货和验收；
3. 退款处理中、结果未知、`NEEDS_ATTENTION` 与授权补偿入口；
4. 售后列表、详情和退款进度的一条连续逆向旅程；
5. 迁移完成后删除确已无消费者的订单/支付遗留全局样式。

V5.3 仍不得修改后端状态机、补偿权限和资金裁决权；完成后再判断 V5 是否收口。
