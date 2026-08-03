# 前端视觉 V6.4.4：管理首页与 V6 收口

> 完成日期：2026-08-03  
> 状态：管理首页完成，V6 全部关闭；下一阶段为 V7 全站交付收口  
> 范围：权限内工作区索引、Analytics 只读投影、日期范围、投影新鲜度与失败保留  
> 硬边界：不跨服务拼接实时事实，不把 Analytics 投影冒充订单、资金、库存或履约最终事实

## 1. 核心结论

旧管理首页同时直接创建 Analytics API、读取员工 session、持有日期和请求状态，并用
四张静态领域卡描述后端能力。它存在三个问题：

1. 页面直接理解 API 和访问令牌，无法隔离员工或 token 切换后的迟到响应；
2. Analytics 返回没有运行时合同，错误日期、重复日汇总或失真的 64 位商品 ID 都可
   进入页面；
3. 静态领域卡更像功能广告，不是员工当前权限内的工作入口。

本批建立以下边界：

```text
Analytics REST
      ↑
Foundation Analytics API
      ↑
entities/admin-analytics
  - operator/token access revision
  - 日期范围与最后请求胜出
  - 运行时投影合同
  - 已知投影失败保留
      ↑
OperationsHomeView
  - 权限内入口
  - 日期筛选
  - 运营投影组合
```

页面只组合员工访问上下文和展示，不再直接创建 Analytics API。

## 2. Analytics 身份合同修复

审查管理首页时发现 `AnalyticsModels.ProductSummary.productId` 仍作为 JSON number
返回。M7 已启用 64 位分布式 ID，超过 JavaScript `Number.MAX_SAFE_INTEGER` 后，
浏览器会在解析 JSON 时静默改变末位。

本批同步修复两个对外身份字段：

- `ProductSummary.productId`：Jackson `ToStringSerializer`；
- `RebuildView.operatorId`：Jackson `ToStringSerializer`；
- Foundation 对应类型改为 `BusinessId`，即十进制字符串；
- 管理首页运行时合同拒绝 number、非十进制和重复商品 ID。

Analytics 后端集成测试新增 HTTP JSON 断言，确认 `productId` 为字符串 `"101"`；
浏览器夹具使用 `2088000000000000101`，DOM、Foundation 类型和 Mock 权威响应逐字符
一致。

本批只改变 JSON 身份表示，不改变 Analytics MySQL 列、Java 内部 `long`、聚合逻辑、
事件消费或重建状态机。

## 3. 访问代次与读取竞态

`admin-analytics` 的有效访问上下文为：

```text
authorized
operatorId
accessToken
```

operator 或 token 任一变化都会增加访问代次和请求代次。旧请求完成后必须同时匹配：

```text
operatorId
accessToken
access revision
request revision
```

否则结果静默作废。相同员工 token 轮换时保留上一份已知只读投影，但旧凭据响应不能
覆盖新请求；员工变化时清空上一员工投影。

日期范围在发请求前校验：

- 必须是有效 `YYYY-MM-DD` 日历日期；
- 开始日期不能晚于结束日期；
- 单次最多 366 天，与后端边界一致；
- 响应 `from/to` 必须与请求完全一致；
- 日汇总必须唯一、升序并位于请求区间；
- 商品汇总必须唯一，最多 8 项；
- 计数必须是非负安全整数，金额必须是非负有限数值；
- 时间必须可解析，收入覆盖订单数不能大于完成订单数。

并发筛选采用最后一次请求胜出，旧日期请求迟到后不能覆盖新范围。

## 4. 失败不伪装为空数据

首次读取失败时，页面明确显示“运营投影读取未完成”。已有投影后的刷新出现网络、
超时、非法响应或 5xx 时：

```text
保留上一份已知投影
  + 保留原投影生成时间
  + 显示本次刷新未确认
  + 不显示空目录或成功
```

Analytics 是可重建、可能滞后的事件读模型。页面持续声明：

- Trade、Payment、Inventory、Fulfillment 等所有者数据库仍是最终事实；
- 关闭订单不等于支付失败；
- 旧事件缺少商品行实付时不能估算收入；
- 每日脉络是已有日期汇总，不是实时趋势线。

## 5. 页面组合与清理

首页改为两段连续结构：

```text
当前员工可用事实工作区
  -> Analytics 运营投影
     -> 区间总览
     -> 商品贡献
     -> 新鲜度与覆盖
     -> 最近七个有事实日期
```

权限入口来自当前员工角色：

- `ADMIN`：全部八个工作区；
- `OPERATOR`：商品、营销、客服、评价及运营统计；
- `WAREHOUSE`：库存与履约，不读取平台运营统计。

页面使用 `PjPageContainer`、`PjSurface`、`PjField`、`PjButton` 和
`PjStatusNotice`，没有新增共享 primitive。现有管理布局模式仍未出现足够稳定、
完全相同的两个以上消费者，因此继续留在页面 composition。

零消费者确认后删除：

- `frontend/admin-web/src/api/analytics.ts`；
- `.admin-domain-grid`；
- 旧 `.analytics-*` 全局 CSS。

新首页样式归当前 SFC 所有，页面不直接使用原始颜色值。

## 6. 三层证据

| 边界 | 代码证明 | 自动化测试 | 真实 Chromium 证据 |
| --- | --- | --- | --- |
| 64 位商品身份不失真 | 后端 `ToStringSerializer`；Foundation `BusinessId`；entity 十进制字符串校验 | Analytics 后端 5/5 含 JSON 字符串断言；entity 拒绝 number ID | DOM 显示 `2088000000000000101`，与 Mock 权威响应逐字符一致 |
| 员工/token 迟到响应隔离 | access/request 双代次，operator 变化清空事实 | entity 延迟请求后切换员工，旧响应不能写入 | 浏览器请求使用当前管理员 Bearer；页面只有当前角色的 8 个入口 |
| 日期与响应合同 | 366 天上限、精确 `from/to`、日汇总唯一升序与范围校验 | entity 覆盖倒置、超长、错身份和最后请求胜出 | F12 自动化捕获真实 `from/to/productLimit=8` 请求 |
| 刷新 503 不伪装空数据 | 已知 dashboard 与刷新 error 分离 | entity 断言 503 后 dashboard/refreshedAt 不变 | Chromium 注入 503 后显示 warning，商品和 19 位 ID 仍可见 |
| 角色权限 | 路由与首页入口共同按角色收窄；Analytics 后端继续 403 WAREHOUSE | 既有路由门禁与 Analytics 集成测试 | 管理员看到 8 个入口；单一 `main`，无越权临时按钮 |
| 窄屏与语义 | 响应式单列、定义列表使用合法 `dt/dd`、设计令牌 | V6.4 axe 与全量 E2E | 1265/1265、320 下 305/305，无根溢出；warning/error 0 |

受控 Mock 只用于稳定验证页面读取、64 位身份和刷新失败，不替代 Analytics 的真实
MySQL、RocketMQ、故障恢复、对账与审计重建证据。真实链继续引用第 67、68、69 号文档。

## 7. 最终门禁

| 门禁 | 结果 |
| --- | ---: |
| 设计系统 | 6 / 6 |
| Foundation | 45 / 45 |
| UI primitives | 5 / 5 |
| Storefront | 171 / 171 |
| Admin | 74 / 74 |
| 前端单元/契约测试合计 | 301 / 301 |
| Analytics entity 专项 | 6 / 6 |
| 分层规则 | 28 / 28 |
| 分层文件 / 相对导入 | 147 / 256 |
| Analytics 后端专项 | 5 / 5 |
| V6.4 Chromium | 17 / 17 |
| Playwright 全量 Mock E2E | 58 / 58 |
| 类型检查、生产构建 | 通过 |
| axe serious / critical | 0 |

生产构建：

```text
Storefront CSS 101.19 kB / gzip 14.33 kB
Storefront JS  356.90 kB / gzip 107.63 kB
Admin CSS      66.61 kB / gzip 9.30 kB
Admin JS       323.72 kB / gzip 95.99 kB
```

自动化过程没有只保留最终绿灯：

1. V6.4 首轮在新首页命中 axe `definition-list`：指标辅助金额是 `dl > div > small`；
   改为第二个合法 `dd` 后完整重跑 17/17；
2. 全量 E2E 首轮 56/58：旧 M4/V2 用例以包含匹配查找“履约与退货”，新首页入口与
   左侧导航形成双匹配；收紧为精确导航名后完整重跑 58/58。

两类失败都没有被标记为偶发或跳过。

## 8. 人工浏览器与清理

内置浏览器在实际 Admin Vite 与 Mock API 上确认：

```text
桌面 clientWidth / scrollWidth = 1265 / 1265
320px clientWidth / scrollWidth = 305 / 305
main = 1
权限入口 = 8
商品 ID = 2088000000000000101
console warning/error = 0
```

人工入口首启漏设 Mock 专用端口变量，Mock 落到默认 18000。该进程未被误认成网络
故障；在核对命令行后精确结束，随后只使用空闲的本地 Mock/Vite 组合完成检查。没有
修改代理、网卡、路由、Docker 或机器级 Node 环境。

最终 `18000/18090/18200/18201` 项目监听均已释放；只保留 Codex 自身 CUA Node。

## 9. 下一坐标

V1–V6 已全部完成。下一批进入 V7，但不自动扩大范围：

1. 全路由视觉与文案审计；
2. 死 CSS、重复组件和临时入口零残留；
3. 图片体积、演示数据、演示账号和启动路径；
4. README 截图、部署说明与 GitHub v1.0 发布材料；
5. 最终全量前后端与浏览器交付门禁。

本批不进入 V7 实施，不启动 Docker 或全套中间件。
